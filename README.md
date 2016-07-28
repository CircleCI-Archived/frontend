# CircleCI's frontend

[![CircleCI](https://circleci.com/gh/circleci/frontend.svg?style=svg)](https://circleci.com/gh/circleci/frontend)

This is an open-source mirror of the code that is running
[CircleCI's](https://circleci.com) frontend. CircleCI provides powerful
Continuous Integration and Deployment with easy setup and maintenance.

Feel free to fork and make contributions. We'll try to get them into the main
application.

Watch [@brandonbloom](https://github.com/brandonbloom)'s Clojure/West talk, [Building CircleCI's Frontend with Om](https://www.youtube.com/watch?v=LNtQPSUi1iQ), for a quick overview.


Want to work with Clojure(Script) full-time? [We're hiring](https://circleci.com/jobs).


## Dependencies and Setup

### Node.js

Install [Node.js](http://nodejs.org/) and node dependencies:

```
npm install
```

Download all of the 3rd-party javascript dependencies:

```
node_modules/.bin/bower install
```

### Clojure

Install [Leiningen](http://leiningen.org/).

**Note:** You can also install leiningen via homebrew with `brew install leiningen`

### nginx

You'll need to install nginx to serve the site over `https` - we
require local development to use SSL to support using development
backends against production APIs in a secure way.

```
# first, install nginx
brew update
brew install nginx

# next, create a self-signed certificate
sudo mkdir /usr/local/etc/nginx/ssl && cd /usr/local/etc/nginx/ssl
sudo openssl req -batch -new \
      -x509 -newkey rsa:2048 -sha256 -nodes -days 365 \
      -subj '/C=US/ST=California/L=San Francisco/O=CircleCI/CN=*.circlehost' \
      -keyout /usr/local/etc/nginx/ssl/star.circlehost.key \
      -out /usr/local/etc/nginx/ssl/star.circlehost.crt
```

### Hosts

In your `/etc/hosts`, add the following line:

```
127.0.0.1 prod.circlehost
```

If you have access to the backend code, you can also add this line:

```
127.0.0.1 dev.circlehost
```
**Note:** Whichever of these you use _must_ be in your `/etc/hosts` to get
`lein figwheel` to run properly (steps in the next section)

## Usage

### Development Processes

You must use foreman (or goreman) to run the frontend and nginx.

```
foreman start # (or) goreman start
```

Then visit https://prod.circlehost:4443 or https://dev.circlehost:4443 (if you
have access and have set it up) in your browser.

### Viewing documentation changes

To see documentation locally you have to compile the docs manifest, like:

```
lein run -m frontend.tasks.http/precompile-assets
```

If you add a new document, you will have to re-run that; but if you just change
one of the existing documents it should show up with just a page refresh.

### Running the Tests

There are two main ways for running tests using the plugin `lein doo`. If you wish to run the tests only once, you can do that with

```
lein doo chrome dev-test once
```

To have a process watch for changes and run the tests every time you make changes, you can run

```
lein doo chrome dev-test auto
```

The default option is `auto`, so it will run in that state if you ommit that argument. You can also use `phantom `, `firefox`, and a variety of our js enviornments to run the tests on. For full documentation read [lein doo's README](https://github.com/bensu/doo/)

### Adding Tests

To add a test you can either create a new test in an existing ns using `deftest`, or create a new ns. However, if you add a new ns it will not automatically be added to the test runner, to do that add it to the list of test ns's in `test-cljs/frontend/test_runner.cljs`.

### Asset Compilation Errors

If you are experiencing errors when building assets the following commands may
help reset all cached state:

```
lein clean
./node_modules/bower/bin/bower install
lein cljsbuild once
```

### Sanity Check

To test that everything worked, visit
https://prod.circlehost:4443/assets/css/app.css and
https://prod.circlehost:4443/assets/js/om-dev.js.stefon in your browser.

### Production & Development Backends

Now you should have everything you need to start hacking on Circle's frontend!

Visit https://prod.circlehost:4443 for the a production backend
with locally build development assets. Again, if you've got access to the
backend code (NOTE: it's not open source), you can run it locally on
`circlehost:8080`. To connect to the development backend, visit
https://dev.circlehost:4443. The dev server will default to dev assets, so you
don't need the query parameter.

### Browser REPL

Figwheel runs its own bREPL, which you'll see on the terminal at your `lein figwheel dev`. You can also run an additional bREPL over nREPL to connect Cider (or similar) to it. First, connect to the nREPL at localhost:7888. (This port is defined in project.clj.) Then, at the Cider REPL:

```clojure
(figwheel-sidecar.repl/cljs-repl "dev")
```

Unfortunately for vim-fireplace, Figwheel doesn't expose the repl-env to pass to `:Piggieback` in any nice way. (If you'd like to work on making it possible, have a look at `figwheel-sidecar.repl/repl-env`. Unfortunately, it takes an entire build map as an argument, rather than just a build ID, as `figwheel-sidecar.repl/cljs-repl` does.) However, you can still run an out-of-browser Rhino REPL using `:Piggieback` (which vim-fireplace will probably run for you automatically.)

### Better Cider Support

I couldn't get the browser repl to work at all using `cider-connect`, and `cider` requires `cemerick.com/piggieback` in order to support a lot of its features, including jumping to definitions.

Here's an alternative method for `cider` users and possibly others who are dependent on `piggieback`.

First, start the `nginx` and `web` processes using foreman.

```
foreman start -f Procfile.alt
```

Then either start a new repl with

```
lein repl
```

or use the `cider-jack-in` command in emacs. Note this is *not* the `cider-jack-in-clojurescript` command. Once the repl is established, execute the following code:

```clojure
frontend.core> (use 'figwheel-sidecar.repl-api)
=> nil
frontend.core> (start-figwheel!)
Figwheel: Starting server at http://localhost:3449
Figwheel: Watching build - dev
Compiling "resources/public/cljs/out/frontend-dev.js" from ["src-cljs" "test-cljs"]...
Successfully compiled "resources/public/cljs/out/frontend-dev.js" in 5.547 seconds.
Figwheel: Starting CSS Watcher for paths  ["resources/assets/css"]
Figwheel: Starting nREPL server on port: 7888
=> #<SystemMap>
frontend.core> (cljs-repl)
Launching ClojureScript REPL for build: dev
Figwheel Controls:
          (stop-autobuild)                ;; stops Figwheel autobuilder
          (start-autobuild [id ...])      ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)        ;; switches autobuilder to different build
          (reset-autobuild)               ;; stops, cleans, and starts autobuilder
          (reload-config)                 ;; reloads build config and resets autobuild
          (build-once [id ...])           ;; builds source one time
          (clean-builds [id ..])          ;; deletes compiled cljs target files
          (print-config [id ...])         ;; prints out build configurations
          (fig-status)                    ;; displays current state of system
  Switch REPL build focus:
          :cljs/quit                      ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object
Prompt will show when Figwheel connects to your application
To quit, type: :cljs/quit
=> nil
cljs.user>
```

Now the middleware should be loaded and emacs `cider` navigation should work.

### CLJS Dev Tools

[Dirac](https://github.com/binaryage/dirac) is a fork of Chrome DevTools that works for ClojureScript.

Requirements:

1. Google Chrome

Running devtools:

1. Run the frontend as usual with `foreman`
2. In another terminal, `cd` to the project directory and run `./script/devtools.sh`. This will open a Chrome window capable of running dev tools.
3. Install the Dirac devtools [Chrome extension](https://chrome.google.com/webstore/detail/dirac-devtools/kbkdngfljkchidcjpnfcgcokkbhlkogi?hl=en) in *the instance of Chrome that was started by `devtools.sh`*. This only needs to be done the first time you use Dirac.
4. Click on the Dirac extension icon to the right of the address bar to open the dev tools.
5. Toggle CLJS on/off by pressing pgUp/pgDn (fn + up/down arrow on Mac) with focus on the prompt field.
