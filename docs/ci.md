# CI
Publication owns automatic release-tag execution and calls gradle.yml once.
gradle.yml remains callable and manually runnable but no longer independently
runs the same nine-job native/consumer verification for a release tag.
All Linux jobs use Depot Ubuntu 24.04, Windows jobs use depot-windows-2025-4,
and Apple jobs use depot-macos-latest. Release commit/tag eligibility,
credentials, signing, provenance, Android ABIs, iOS variants, native desktop
engines and complete consumer tests remain publication gates.
