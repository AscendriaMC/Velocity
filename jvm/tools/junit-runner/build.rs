fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Both halves: we serve TestExecutor and call TestOrchestrator.
    tonic_build::configure().build_server(true).build_client(true).compile_protos(
        &["proto/test.proto", "proto/host_sharing.proto"],
        &["proto"],
    )?;
    Ok(())
}
