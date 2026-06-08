# Self-signed certificates for development

This folder contains the TLS certificates to support local HTTPS connections for development purposes.

Run the associated [createcerts.sh](createcerts.sh) shell script to (re)create the certificates. New hosts can be added by editing this script (add to HOSTS variable).

The root CA should be trusted by clients (like a browser).

**These certificates should ONLY be trusted by sandboxed clients!**
