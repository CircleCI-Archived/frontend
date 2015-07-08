<!--

title: Start background processes from circle.yml
last_updated: Feb 5, 2014

-->

Starting a background process from [circle.yml](/docs/configuration)
is entirely possible, but it is not done by adding `&`
to the end of your command line. Instead, you set the background flag on the command.  For example:

```
machine:
  post:
    - ./daemon:
          background: true
```

If your server takes more than a moment to start, it might be worth adding a
`sleep` to prevent problems when the tests start:

```
machine:
  post:
    - ./daemon:
          background: true
    - sleep 5
```

Or better yet, block at the start of your test suite until the
connection is available.  This speeds up your tests by starting them
in parallel with your daemon, but doesn't suffer from any race
conditions where your tests start before your daemon does.

If you do not redirect stdout and stderr in the command, they are captured
into files in the artifact directory.  (e.g., "stdout_daemon_7452.txt")

(Explanation: The background flag prevents your process being killed
in the remote environment.  If a process is running (even in the
background) when the remote connection is closed, it will get killed
by the hangup (HUP) signal.  The background flag uses the "nohup"
command to prevent this.  The command that gets issued is similar to
`nohup bash -c "./daemon &"`).
