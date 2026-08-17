//! Known-answer tests.
//!
//! The CFB8 vectors were produced by OpenSSL 3.5.5 itself:
//!
//!   openssl enc -aes-128-cfb8 -nopad -K <hex> -iv <hex> -in pt.bin
//!
//! which is the exact code path this crate replaces. If these ever go red, the
//! proxy is emitting a stream real clients cannot read — nothing else in the
//! suite matters more.

mod common;
use common::{hex, to_hex};
use mccrypt::{soft, Backend, Cfb8, RoundKeys};

/// key == iv == b"0123456789abcdef", plaintext 0x00..=0xff
const KEY1: &str = "30313233343536373839616263646566";
const CT1: &str = "72301ed9f93190becdc3f019ba3e19d3d35c752403222b4167ca8f4f25e83147\
4838c8b906a976bdf5eb1fab846dbf9f8702ce929ffe423c3ca58ba982f862fe\
9919f45a6fc6ab1ca0ea7ae9270c66444890a9791924430b30f9ad8d192a1ef3\
575fdda53dd465a8566070b65212ecaab9ef127147afb74ab707c308b655ef49\
6daaaf7e8ab63f101db60b9575526813294f65486202422478206f8fee8084f2\
c7f76d1d077149372eb685ebf69125133c4bc2c0e07e7e20b5872e2b4affa399\
f817a358e24b1fdfdd643160746de0d9d402605a87b8074bdf38e389ceae5292\
dc5bd070bf300b671ff50c14895bfc5ca236a5342b0752f366bb729cb5880d97";

/// key == iv == 00..0f, plaintext 0xff..=0x00
const KEY2: &str = "000102030405060708090a0b0c0d0e0f";
const CT2: &str = "f5db3c77600cd8e5d73eff6855b341525391e228e66c32575e5f887b8832a02d\
d5b4f0649cf391dba2e13491f696999e518d5e48c64ee0683f040abee88d17ab\
781be3efc5470ca1b7d390f695744e4abd00f48fc7011f3c0ded0dc620b4a19e\
d09fe13df9104d3996404111f0441efb522092b2485d6b6c4afca858fdf13a2d\
30f7768e21dfa3e21b4ae1810bb226b1724be000c60809c084e6caf5bd24f458\
652c1ad552e57909c9b457b7d34e06a0ff740d01ca137ee772d0a34272fcf72a\
bfb22dc07f9eb205aac9fc60cfa47ab8f4700cae179ebe41852eb91fdb89cdbe\
7df191a0a035a64f4da7f228c65766da9930fe42ef9cc4acef2db6f0337808cc";

fn key_of(s: &str) -> [u8; 16] {
    hex(s).try_into().unwrap()
}

/// FIPS-197 C.1: validates the key schedule and the raw block function, which
/// the CFB8 vectors would otherwise only cover indirectly.
#[test]
fn fips197_block() {
    let rk = RoundKeys::expand(&key_of("000102030405060708090a0b0c0d0e0f"));
    let pt: [u8; 16] = hex("00112233445566778899aabbccddeeff").try_into().unwrap();
    assert_eq!(
        to_hex(&soft::encrypt_block(&rk, &pt)),
        "69c4e0d86a7b0430d8cdb78070b4c55a"
    );
}

fn check(backend: Backend, key_hex: &str, plaintext: Vec<u8>, ct_hex: &str) {
    let key = key_of(key_hex);
    let expected = hex(ct_hex);

    let mut buf = plaintext.clone();
    Cfb8::with_backend(&key, true, backend).process(&mut buf);
    assert_eq!(
        to_hex(&buf),
        to_hex(&expected),
        "{}: encryption diverged from OpenSSL",
        backend.name()
    );

    let mut buf = expected.clone();
    Cfb8::with_backend(&key, false, backend).process(&mut buf);
    assert_eq!(
        to_hex(&buf),
        to_hex(&plaintext),
        "{}: decryption diverged from OpenSSL",
        backend.name()
    );
}

#[test]
fn openssl_vectors() {
    let asc: Vec<u8> = (0..=255u8).collect();
    let desc: Vec<u8> = (0..=255u8).rev().collect();
    for b in Backend::available() {
        check(b, KEY1, asc.clone(), CT1);
        check(b, KEY2, desc.clone(), CT2);
    }
}

/// The same vector fed in arbitrary pieces. Velocity hands us whatever netty
/// happened to read, so stream state has to survive call boundaries exactly.
#[test]
fn split_calls_match_oneshot() {
    let key = key_of(KEY1);
    let pt: Vec<u8> = (0..=255u8).collect();
    let expected = hex(CT1);

    for b in Backend::available() {
        for split in [1usize, 2, 7, 8, 9, 15, 16, 17, 63, 64, 65, 127, 255] {
            let mut c = Cfb8::with_backend(&key, true, b);
            let mut buf = pt.clone();
            let mut off = 0;
            while off < buf.len() {
                let n = split.min(buf.len() - off);
                c.process(&mut buf[off..off + n]);
                off += n;
            }
            assert_eq!(
                to_hex(&buf),
                to_hex(&expected),
                "{}: split {} broke encryption stream state",
                b.name(),
                split
            );

            let mut c = Cfb8::with_backend(&key, false, b);
            let mut buf = expected.clone();
            let mut off = 0;
            while off < buf.len() {
                let n = split.min(buf.len() - off);
                c.process(&mut buf[off..off + n]);
                off += n;
            }
            assert_eq!(
                to_hex(&buf),
                to_hex(&pt),
                "{}: split {} broke decryption stream state",
                b.name(),
                split
            );
        }
    }
}

#[test]
fn empty_input_is_a_no_op() {
    for b in Backend::available() {
        let key = key_of(KEY1);
        let mut c = Cfb8::with_backend(&key, true, b);
        c.process(&mut []);
        let mut buf = vec![0u8; 4];
        c.process(&mut buf);
        // Same as if the empty call had never happened.
        let mut c2 = Cfb8::with_backend(&key, true, b);
        let mut buf2 = vec![0u8; 4];
        c2.process(&mut buf2);
        assert_eq!(buf, buf2, "{}", b.name());
    }
}
