//! JNI surface, ABI-identical to Velocity's `jni_cipher_openssl.c`.
//!
//! The exported symbols are exactly the three natives declared by
//! `com.velocitypowered.natives.encryption.OpenSslCipherImpl`, so the resulting
//! shared object drops into `velocity-natives` with no Java changes at all.

use core::ffi::c_char;
use jni_sys::{jboolean, jbyteArray, jclass, jint, jintArray, jlong, jlongArray, JNIEnv, JNI_TRUE};

use crate::{encrypt_batch, Cfb8, Job, LANES};

const KEY_LEN: jint = 16;

unsafe fn throw(env: *mut JNIEnv, class: *const c_char, msg: *const c_char) {
    let find = (**env).FindClass.unwrap();
    let cls = find(env, class);
    if !cls.is_null() {
        let throw_new = (**env).ThrowNew.unwrap();
        throw_new(env, cls, msg);
    }
}

/// `init(byte[] key, boolean encrypt) -> long`
///
/// Matches the C implementation's contract: a key that is not 16 bytes raises
/// `IllegalArgumentException("cipher not 16 bytes")` and returns 0.
#[no_mangle]
pub extern "system" fn Java_com_velocitypowered_natives_encryption_OpenSslCipherImpl_init(
    env: *mut JNIEnv,
    _class: jclass,
    key: jbyteArray,
    encrypt: jboolean,
) -> jlong {
    unsafe {
        if key.is_null() {
            throw(
                env,
                c"java/lang/IllegalArgumentException".as_ptr(),
                c"cipher not 16 bytes".as_ptr(),
            );
            return 0;
        }
        let len = (**env).GetArrayLength.unwrap()(env, key);
        if len != KEY_LEN {
            throw(
                env,
                c"java/lang/IllegalArgumentException".as_ptr(),
                c"cipher not 16 bytes".as_ptr(),
            );
            return 0;
        }

        // The length is bounded, so a region copy avoids the pinning/critical
        // error handling the C impl also sidesteps.
        let mut raw = [0i8; KEY_LEN as usize];
        (**env).GetByteArrayRegion.unwrap()(env, key, 0, len, raw.as_mut_ptr());
        if (**env).ExceptionCheck.unwrap()(env) == JNI_TRUE {
            return 0;
        }

        let key_bytes: [u8; 16] = core::mem::transmute(raw);
        Box::into_raw(Box::new(Cfb8::new(&key_bytes, encrypt != 0))) as jlong
    }
}

/// `process(long ctx, long source, int len, long dest)`
///
/// Velocity always passes `source == dest` (it encrypts netty direct buffers in
/// place), but the C impl accepts distinct pointers, so this does too.
#[no_mangle]
pub extern "system" fn Java_com_velocitypowered_natives_encryption_OpenSslCipherImpl_process(
    _env: *mut JNIEnv,
    _class: jclass,
    ctx: jlong,
    source: jlong,
    len: jint,
    dest: jlong,
) {
    if ctx == 0 || len <= 0 {
        return;
    }
    unsafe {
        let cipher = &mut *(ctx as *mut Cfb8);
        let src = source as *const u8;
        let dst = dest as *mut u8;
        let n = len as usize;
        if src != dst.cast_const() {
            // memmove: the caller is free to hand us overlapping regions.
            core::ptr::copy(src, dst, n);
        }
        cipher.process(core::slice::from_raw_parts_mut(dst, n));
    }
}

/// `free(long ptr)` — tolerates 0, as `EVP_CIPHER_CTX_free(NULL)` does.
#[no_mangle]
pub extern "system" fn Java_com_velocitypowered_natives_encryption_OpenSslCipherImpl_free(
    _env: *mut JNIEnv,
    _class: jclass,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }
    unsafe { drop(Box::from_raw(ptr as *mut Cfb8)) }
}

/// Jobs pulled out of the JVM per pass. Bounded so this needs no allocation at
/// any batch size — a larger batch is simply processed in several passes, which
/// is transparent because each job appears exactly once and streams never span
/// two jobs within a call.
const JOBS_PER_PASS: usize = 64;

/// `batchLanes() -> int`
///
/// Doubles as the capability probe. Velocity resolves JNI symbols lazily, so on
/// an OpenSSL-built cipher library this call raises `UnsatisfiedLinkError` and
/// the proxy quietly keeps the one-frame-at-a-time path.
#[no_mangle]
pub extern "system" fn Java_com_velocitypowered_natives_encryption_OpenSslCipherImpl_batchLanes(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    LANES as jint
}

/// `processBatch(long[] ctxs, long[] addrs, int[] lens, int count)`
///
/// Encrypts `count` frames in one crossing, filling all eight lanes.
///
/// The `ctxs` must be pairwise distinct within a single call: two lanes sharing
/// a cipher would interleave its shift register and corrupt that connection.
/// The coordinator on the Java side enforces this by emitting at most one frame
/// per channel per call.
#[no_mangle]
pub extern "system" fn Java_com_velocitypowered_natives_encryption_OpenSslCipherImpl_processBatch(
    env: *mut JNIEnv,
    _class: jclass,
    ctxs: jlongArray,
    addrs: jlongArray,
    lens: jintArray,
    count: jint,
) {
    if count <= 0 {
        return;
    }
    unsafe {
        let array_len = (**env).GetArrayLength.unwrap();
        if ctxs.is_null()
            || addrs.is_null()
            || lens.is_null()
            || array_len(env, ctxs) < count
            || array_len(env, addrs) < count
            || array_len(env, lens) < count
        {
            throw(
                env,
                c"java/lang/IllegalArgumentException".as_ptr(),
                c"batch arrays shorter than count".as_ptr(),
            );
            return;
        }

        let get_longs = (**env).GetLongArrayRegion.unwrap();
        let get_ints = (**env).GetIntArrayRegion.unwrap();
        let total = count as usize;
        let mut done = 0usize;

        while done < total {
            let m = (total - done).min(JOBS_PER_PASS);
            let mut ctx_buf = [0i64; JOBS_PER_PASS];
            let mut addr_buf = [0i64; JOBS_PER_PASS];
            let mut len_buf = [0i32; JOBS_PER_PASS];
            get_longs(env, ctxs, done as jint, m as jint, ctx_buf.as_mut_ptr());
            get_longs(env, addrs, done as jint, m as jint, addr_buf.as_mut_ptr());
            get_ints(env, lens, done as jint, m as jint, len_buf.as_mut_ptr());
            if (**env).ExceptionCheck.unwrap()(env) == JNI_TRUE {
                return;
            }

            let mut jobs = [Job {
                cipher: core::ptr::null_mut(),
                buf: core::ptr::null_mut(),
                len: 0,
            }; JOBS_PER_PASS];
            let mut k = 0usize;
            for i in 0..m {
                // A closed channel can leave a zeroed slot behind; skip rather
                // than dereference it.
                if ctx_buf[i] != 0 && addr_buf[i] != 0 && len_buf[i] > 0 {
                    jobs[k] = Job {
                        cipher: ctx_buf[i] as *mut Cfb8,
                        buf: addr_buf[i] as *mut u8,
                        len: len_buf[i] as usize,
                    };
                    k += 1;
                }
            }
            encrypt_batch(&jobs[..k]);
            done += m;
        }
    }
}
