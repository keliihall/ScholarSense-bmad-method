# Base runtime-role seed

This directory records a product-neutral contract for running the same backend JAR as either `web-api` or `worker`. It is not a container manifest, CI definition, production environment approval, or artifact-promotion record.

The web role exposes only the Spring Boot health probe seed until owning capability Stories add business endpoints. The worker role starts no HTTP server; its base probe is process liveness. All environment-specific values are injected externally and sensitive material is represented only by an environment-scoped reference.

