# MateLink TeslaMate Archive Bridge

This profile reads completed TeslaMate rows through a PostgreSQL read-only connection and uploads source-bound batches. It never receives Tesla credentials, writes the source database, or logs tokens, VINs, coordinates, or response bodies.

The cursor is replaced atomically only after a successful `2xx` archive response. Failed uploads leave the previous cursor in place for retry.

Run tests with:

```powershell
go test ./...
```
