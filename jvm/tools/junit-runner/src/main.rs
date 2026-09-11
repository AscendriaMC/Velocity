//! A buck2 test executor that reports each JUnit test, not each target.
//!
//! buck2's built-in runner treats a test command as opaque: it runs it and maps the exit code to a
//! single result for the whole target, so a java_test of 212 tests reports one pass and a failure
//! names the module rather than the method. This executor asks a JUnit command for its XML report
//! and reports every `<testcase>` in it.
//!
//! Wired with `[test] v2_test_executor`, so it works with any buck2 -- no patched binary. The
//! protocol is buck2-internal and vendored in proto/ (see the NOTICE there for the revision).
//!
//! buck2 starts us with two ends of a socketpair it has already connected: we serve the
//! TestExecutor service on one and call the TestOrchestrator on the other. Order matters -- the
//! server has to be up before the client handshakes, or each end waits for the other.

use std::fs;
use std::os::fd::FromRawFd;
use std::os::fd::RawFd;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Duration;

use clap::Parser;
use hyper_util::rt::TokioIo;
use quick_xml::Reader;
use quick_xml::XmlVersion;
use quick_xml::events::BytesStart;
use quick_xml::events::Event;
use tokio::net::UnixStream;
use tokio::sync::mpsc;
use tonic::Request;
use tonic::Response;
use tonic::Status;
use tonic::transport::Endpoint;
use tonic::transport::Server;

pub mod buck {
    pub mod test {
        tonic::include_proto!("buck.test");
    }
    pub mod host_sharing {
        tonic::include_proto!("buck.host_sharing");
    }
}

use buck::test::arg_value_content::Value as ArgContent;
use buck::test::execution_status::Status as ExecStatus;
use buck::test::test_executor_server::TestExecutor;
use buck::test::test_executor_server::TestExecutorServer;
use buck::test::test_orchestrator_client::TestOrchestratorClient;
use buck::test::test_stage::Item as StageItem;
use buck::host_sharing::WeightClass;
use buck::host_sharing::host_sharing_requirements::Requirements as HostSharingKind;
use buck::host_sharing::host_sharing_requirements::Shared;
use buck::host_sharing::weight_class::Value as WeightValue;
use buck::host_sharing::HostSharingRequirements;
use buck::test::{
    ArgValue, ArgValueContent, ConfiguredTargetHandle, DeclaredOutput, Empty, EndOfTestResultsRequest,
    ExecuteRequest2, ExecutionResult2, ExternalRunnerSpec, ExternalRunnerSpecRequest,
    OutputName, ReportTestResultRequest, TestExecutable, TestResult,
    TestStage, TestStatus, Testing, execute_response2, output,
};

/// The `type` an ExternalRunnerTestInfo declares when its command is a JUnit ConsoleLauncher.
const JUNIT_TEST_TYPE: &str = "junit";
/// The declared output the reports are written into, and read back out of.
const JUNIT_REPORTS: &str = "junit-reports";

#[derive(Parser)]
struct Opts {
    /// One end of a socketpair buck2 already connected; we serve the executor service on it.
    #[clap(long)]
    executor_fd: RawFd,

    /// The other end; our calls to the orchestrator go out on it.
    #[clap(long)]
    orchestrator_fd: RawFd,

    /// Max seconds for one test command.
    #[clap(long, default_value = "600")]
    timeout: u64,

    /// buck2 passes these whether or not an executor wants them. Declared so clap accepts the
    /// command line it is actually given; none of them change what we do.
    #[clap(long, hide = true)]
    buck_trace_id: Option<String>,
    #[clap(long, hide = true)]
    config_entry: Vec<String>,
    #[clap(long, hide = true)]
    buck_test_info: Option<String>,
    #[clap(long, hide = true)]
    env: Vec<String>,
    #[clap(trailing_var_arg = true, allow_hyphen_values = true)]
    rest: Vec<String>,
}

/// Receives the targets to test. Each is handed to the run loop; the channel closing is buck2
/// saying there are no more.
struct Executor {
    specs: mpsc::UnboundedSender<Option<ExternalRunnerSpec>>,
}

#[tonic::async_trait]
impl TestExecutor for Executor {
    async fn external_runner_spec(
        &self,
        request: Request<ExternalRunnerSpecRequest>,
    ) -> Result<Response<Empty>, Status> {
        if let Some(spec) = request.into_inner().test_spec {
            let _ = self.specs.send(Some(spec));
        }
        Ok(Response::new(Empty {}))
    }

    async fn end_of_test_requests(&self, _: Request<Empty>) -> Result<Response<Empty>, Status> {
        let _ = self.specs.send(None);
        Ok(Response::new(Empty {}))
    }

    async fn unstable_heap_dump(
        &self,
        _: Request<buck::test::UnstableHeapDumpRequest>,
    ) -> Result<Response<buck::test::UnstableHeapDumpResponse>, Status> {
        Ok(Response::new(buck::test::UnstableHeapDumpResponse {}))
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let opts = Opts::parse();

    // SAFETY: buck2 passes fds it has just created and cleared CLOEXEC on. If they are not what it
    // says, the worst case is a bad file descriptor, which is all we could do about it anyway.
    let executor_io = unsafe { std::os::unix::net::UnixStream::from_raw_fd(opts.executor_fd) };
    let orchestrator_io =
        unsafe { std::os::unix::net::UnixStream::from_raw_fd(opts.orchestrator_fd) };
    executor_io.set_nonblocking(true)?;
    orchestrator_io.set_nonblocking(true)?;
    let executor_io = UnixStream::from_std(executor_io)?;
    let orchestrator_io = UnixStream::from_std(orchestrator_io)?;

    let (spec_tx, mut spec_rx) = mpsc::unbounded_channel();

    // Serve first: buck2 is waiting for this side before it will talk on the other.
    let server = tokio::spawn(async move {
        Server::builder()
            .add_service(TestExecutorServer::new(Executor { specs: spec_tx }))
            .serve_with_incoming(tokio_stream::once(Ok::<_, std::io::Error>(executor_io)))
            .await
    });

    // tonic dials an address; this one is already connected, so the connector just hands it over.
    let io = Arc::new(Mutex::new(Some(orchestrator_io)));
    let channel = Endpoint::try_from("http://[::]:50051")?
        .connect_with_connector(tower::service_fn(move |_| {
            let io = io.clone();
            async move {
                io.lock()
                    .unwrap()
                    .take()
                    .map(TokioIo::new)
                    .ok_or_else(|| std::io::Error::other("orchestrator connected twice"))
            }
        }))
        .await?;
    let mut client = TestOrchestratorClient::new(channel);

    let timeout = Duration::from_secs(opts.timeout);
    let mut failed = false;
    while let Some(Some(spec)) = spec_rx.recv().await {
        failed |= run_one(&mut client, spec, timeout).await?;
    }

    client
        .end_of_test_results(EndOfTestResultsRequest {
            exit_code: i32::from(failed),
        })
        .await?;
    server.abort();
    Ok(())
}

/// Run one target's command and report what it produced. True when something failed.
async fn run_one(
    client: &mut TestOrchestratorClient<tonic::transport::Channel>,
    spec: ExternalRunnerSpec,
    timeout: Duration,
) -> Result<bool, Box<dyn std::error::Error>> {
    let target = spec.target.clone().unwrap_or_default();
    let handle = target.handle.unwrap_or_default();
    let junit = spec.test_type == JUNIT_TEST_TYPE;

    // A junit spec is a JUnit Platform ConsoleLauncher, so it can be asked for its XML report. The
    // directory is a declared output rather than a path we invent: buck2 creates it, substitutes
    // it into the argument, and hands the contents back even when the test ran on a remote worker.
    let mut cmd: Vec<ArgValue> = spec
        .command
        .into_iter()
        .map(|value| ArgValue {
            content: Some(ArgValueContent {
                value: Some(ArgContent::SpecValue(value)),
            }),
            format: None,
        })
        .collect();
    let mut pre_create_dirs = Vec::new();
    if junit {
        cmd.push(ArgValue {
            content: Some(ArgValueContent {
                value: Some(ArgContent::DeclaredOutput(OutputName {
                    name: JUNIT_REPORTS.to_owned(),
                })),
            }),
            format: Some(buck::test::ArgFormat {
                format: "--reports-dir={}".to_owned(),
            }),
        });
        pre_create_dirs.push(DeclaredOutput {
            name: JUNIT_REPORTS.to_owned(),
            supports_remote: false,
            ttl_config: None,
        });
    }

    let env = spec
        .env
        .into_iter()
        .map(|(key, value)| buck::test::EnvironmentVariable {
            key,
            value: Some(ArgValue {
                content: Some(ArgValueContent {
                    value: Some(ArgContent::SpecValue(value)),
                }),
                format: None,
            }),
        })
        .collect();

    let response = client
        .execute2(ExecuteRequest2 {
            timeout: Some(prost_types::Duration {
                seconds: timeout.as_secs() as i64,
                nanos: 0,
            }),
            // One permit, shared: the same thing buck2's own runner asks for. The oneof has to
            // be set -- an empty message is rejected as "Missing `requirements`".
            host_sharing_requirements: Some(HostSharingRequirements {
                requirements: Some(HostSharingKind::Shared(Shared {
                    weight_class: Some(WeightClass {
                        value: Some(WeightValue::Permits(1)),
                    }),
                })),
            }),
            test_executable: Some(TestExecutable {
                stage: Some(TestStage {
                    item: Some(StageItem::Testing(Testing {
                        suite: target.target.clone(),
                        testcases: Vec::new(),
                        variant: None,
                        repeat_count: None,
                    })),
                }),
                target: Some(handle.clone()),
                cmd,
                env,
                pre_create_dirs,
            }),
            executor_override: None,
            required_local_resources: Vec::new(),
            disable_test_execution_caching: false,
        })
        .await?
        .into_inner();

    let result = match response.response {
        Some(execute_response2::Response::Result(result)) => result,
        _ => return Ok(true), // cancelled
    };

    let name = format!("{}//{}:{}", target.cell, target.package, target.target);
    let cases = junit_results(&handle.id, &result);
    if cases.is_empty() {
        // Not a junit run, or one that died before writing a report: say what the exit code says,
        // which is all buck2's own runner ever says and all we honestly can.
        let case = exit_code_result(name, &handle, &result);
        let failed = case.status != TestStatus::Pass as i32;
        client
            .report_test_result(ReportTestResultRequest { result: Some(case) })
            .await?;
        return Ok(failed);
    }

    let mut failed = false;
    for case in cases {
        failed |= case.status == TestStatus::Fail as i32;
        client
            .report_test_result(ReportTestResultRequest { result: Some(case) })
            .await?;
    }
    Ok(failed)
}

fn exit_code_result(
    name: String,
    handle: &ConfiguredTargetHandle,
    result: &ExecutionResult2,
) -> TestResult {
    let status = match result.status.as_ref().and_then(|s| s.status.as_ref()) {
        Some(ExecStatus::Finished(0)) => TestStatus::Pass,
        Some(ExecStatus::Finished(_)) => TestStatus::Fail,
        Some(ExecStatus::TimedOut(_)) => TestStatus::Timeout,
        None => TestStatus::Unknown,
    };
    TestResult {
        target: Some(handle.clone()),
        name,
        status: status as i32,
        msg: None,
        duration: result.execution_time.clone(),
        details: String::from_utf8_lossy(
            &result
                .stderr
                .as_ref()
                .and_then(|s| s.item.clone())
                .map(|buck::test::execution_stream::Item::Inline(bytes)| bytes)
                .unwrap_or_default(),
        )
        .into_owned(),
        max_memory_used_bytes: result.max_memory_used_bytes,
    }
}
/// Every `<testcase>` in the reports the run left behind.
///
/// Empty when the run declared none, when the executor kept them remote, or when the launcher
/// wrote nothing -- the caller falls back to the exit code in all three.
fn junit_results(target: &i64, result: &ExecutionResult2) -> Vec<TestResult> {
    let Some(dir) = result
        .outputs
        .iter()
        .find(|entry| {
            entry.declared_output.as_ref().map(|o| o.name.as_str()) == Some(JUNIT_REPORTS)
        })
        .and_then(|entry| entry.output.as_ref())
        .and_then(|o| match o.value.as_ref() {
            Some(output::Value::LocalPath(path)) => Some(path),
            _ => None,
        })
    else {
        return Vec::new();
    };

    let mut files = Vec::new();
    collect_xml(Path::new(dir), &mut files);
    files.sort();

    files
        .iter()
        .filter_map(|file| fs::read_to_string(file).ok())
        .flat_map(|text| parse_junit_xml(&text, target))
        .collect()
}

fn collect_xml(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_xml(&path, out);
        } else if path.extension().is_some_and(|e| e == "xml") {
            out.push(path);
        }
    }
}

/// The `<testcase>` elements of one JUnit XML report.
///
/// Deliberately forgiving: a report that will not parse yields nothing and the caller falls back
/// to the exit code, which beats failing a run over a malformed file.
fn parse_junit_xml(text: &str, target: &i64) -> Vec<TestResult> {
    fn attr(e: &BytesStart, key: &str) -> Option<String> {
        e.attributes()
            .flatten()
            .find(|a| a.key.as_ref() == key)
            .and_then(|a| {
                a.normalized_value(XmlVersion::Implicit1_0)
                    .ok()
                    .map(|v| v.into_owned())
            })
    }

    let mut reader = Reader::from_str(text);
    let mut results: Vec<TestResult> = Vec::new();
    let mut open: Option<TestResult> = None;

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) | Ok(Event::Empty(e)) => match e.name().0 {
                "testcase" => {
                    if let Some(done) = open.take() {
                        results.push(done);
                    }
                    let name = match (attr(&e, "classname"), attr(&e, "name")) {
                        (Some(class), Some(case)) => format!("{class}.{case}"),
                        (None, Some(case)) => case,
                        _ => continue,
                    };
                    let seconds: f64 = attr(&e, "time").and_then(|t| t.parse().ok()).unwrap_or(0.0);
                    open = Some(TestResult {
                        target: Some(ConfiguredTargetHandle { id: *target }),
                        name,
                        status: TestStatus::Pass as i32,
                        msg: None,
                        duration: Some(prost_types::Duration {
                            seconds: seconds.trunc().max(0.0) as i64,
                            nanos: (seconds.fract().max(0.0) * 1e9) as i32,
                        }),
                        details: String::new(),
                        max_memory_used_bytes: None,
                    });
                }
                tag @ ("failure" | "error" | "skipped") => {
                    if let Some(case) = open.as_mut() {
                        case.status = if tag == "skipped" {
                            TestStatus::Skip as i32
                        } else {
                            TestStatus::Fail as i32
                        };
                        case.details = attr(&e, "message").unwrap_or_default();
                    }
                }
                _ => {}
            },
            // The stack trace under a <failure>, which is the useful half of a failure.
            Ok(Event::Text(text)) => {
                if let Some(case) = open.as_mut() {
                    if case.status == TestStatus::Fail as i32 {
                        let body = text.trim();
                        if !body.is_empty() {
                            case.details.push('\n');
                            case.details.push_str(body);
                        }
                    }
                }
            }
            Ok(Event::End(e)) if e.name().0 == "testcase" => {
                if let Some(done) = open.take() {
                    results.push(done);
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    if let Some(done) = open.take() {
        results.push(done);
    }
    results
}
