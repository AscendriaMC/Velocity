load("@jvm//rules:java.bzl", "export_file")

# The version lives in gradle.properties and nowhere else; the assembled jars read it from there.
export_file(
    name = "gradle.properties",
    src = "gradle.properties",
    visibility = ["PUBLIC"],
)
