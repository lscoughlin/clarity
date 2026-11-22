# Changelog

## 1.1.0

- Add `--idle-timeout=<duration>` to `clarity daemon`. Use seconds, or
  the suffix `s`, `m` or `h`. The value `0` never stops the daemon when
  idle. The default stays 10 minutes.
- Add the environment variable `CLARITY_DAEMON_IDLE_TIMEOUT`. The
  option wins over the variable. A bad value exits with status 2.
- Log `idle timeout disabled` at start when the timeout is 0. The
  `idle timeout reached` log line now includes the configured value.
- SIGTERM now stops the daemon cleanly: it finishes running queries,
  removes the socket file and exits with status 0.
- README: add an options table and a "Run as a sidecar" section.

## 1.0.0

- First release.
