<!--

title: Interacting with the browser on CircleCI's VM
last_updated: October 20, 2014

-->

Integration tests can be hard to debug, especially when they're running on a remote machine.
There are three good ways to debug browser tests on CircleCI.

## Screenshots and artifacts

At the end of a build on CircleCI, we will gather up all [build artifacts](/docs/build-artifacts)
and make them available from your build. This allows you to save screenshots as part of your build,
and then view them when the build finishes.

Saving screenshots is straightforward: it's a built-in feature in webkit and selenium, and supported by most test suites:

*   [Manually, using Selenium directly](http://docs.seleniumhq.org/docs/04_webdriver_advanced.jsp#remotewebdriver)
*   [Automaticaly on failure, using Cucumber](https://github.com/mattheworiordan/capybara-screenshot)
*   [Automaticaly on failure, using Behat and Mink](https://gist.github.com/michalochman/3175175)

To make this work with build artifacts, you need to save the screenshot to the
`$CIRCLE_ARTIFACTS` directory.

## Interact with the browser over VNC

### Spawning your own X Server

VNC allows you to view and interact with the browser that is running your tests. This will only work if you're using a driver that runs a real browser. You will be able to interact with a browser that Selenium controls, but phantomjs is headless -- there is nothing to interact with.

Before you start, make sure you have a VNC viewer installed. If you're using OSX, I recommend
[Chicken of the VNC](http://sourceforge.net/projects/chicken/).
[RealVNC](http://www.realvnc.com/download/viewer/) is also available on most platforms.

First, [start an SSH build](/docs/ssh-build)
to a CircleCI VM. When you connect to the machine, add the -L flag and forward the remote port 5901 to the local port 5902:

```
daniel@mymac$ ssh -p PORT ubuntu@IP_ADDRESS -L 5902:localhost:5901
```

You should be connected to the Circle VM, now start the VNC server:

```
ubuntu@box159:~$ vnc4server -geometry 1280x1024 -depth 24; export DISPLAY=:1.0
```

Enter the password `password` when it prompts you for a password. Your connection is secured with SSH, so there is no need for a strong password. You do need to enter a password to start the VNC server.

Start your VNC viewer and connect to `localhost:5902`, enter the password you entered when it prompts you for a password. You should see a display containing a terminal window. You can ignore any warnings about an insecure or unencrypted connection. Your connection is secured through the SSH tunnel.

Now you can run your integration tests from the command line and watch the browser for unexpected behavior. You can even interact with the browser &mdash; it's as if the tests were running on your local machine!

### Sharing Circle's X Server

If you do not want to just run your tests on Circle, but also attach to a running browser session, this can be achieved by attaching a VNC server to X.

Following configuration will download [`x11vnc`](http://www.karlrunge.com/x11vnc/index.html) and start it before your tests:

```
dependencies:
  - sudo apt-get install -y x11vnc
test:
  pre:
    - x11vnc -forever -nopw:
        background: true
```

Now [start an SSH build](/docs/ssh-build) and connect to it with your tests running. You can either use a VNC viewer that is capable of SSH tunneling, or set up a tunnel on your own:

```
$ ssh -p PORT ubuntu@IP_ADDRESS -L 5900:localhost:5900
```

## X11 forwarding over SSH

CircleCI also supports X11 forwarding over SSH. X11 forwarding is similar to VNC -- you can interact with the browser running on CircleCI from your local machine.

Before you start, make sure you have an X Window System on your computer. If you're using OSX, I recommend
[XQuartz](http://xquartz.macosforge.org/landing/).

With X set up on your system, [start an SSH build](/docs/ssh-build)
to a CircleCI VM, using the `-X` flag to set up forwarding:

```
daniel@mymac$ ssh -X -p PORT ubuntu@IP_ADDRESS
```

This will start an SSH session with X11 forwarding enabled.
To connect your VM's display to your machine, set the display environment variable to localhost:10.0

```
ubuntu@box10$ export DISPLAY=localhost:10.0
```

Check that everything is working by starting xclock.

```
ubuntu@box10$ xclock
```

You can kill xclock with Ctrl+c after it appears on your desktop.

Now you can run your integration tests from the command line and watch the browser for unexpected behavior. You can even interact with the browser &mdash; it's as if the tests were running on your local machine!
