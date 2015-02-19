<!--

title: Continuous Integration and Continuous Deployment with Node.js
short_title: Node.js
last_updated: February 13, 2015

-->

Circle has great support for Node.js applications.
We inspect your code before each build to infer your settings, dependencies, and test steps.

If your project has any special requirements, you can augment or override our
inferred commands from a [circle.yml](/docs/configuration)
file checked into your repo's root directory. You can also add [deployment](/docs/configuration#deployment)
commands that will run after a green build.

### Version

Circle has [several Node versions](/docs/environment#nodejs)
pre-installed.
We use `{{ versions.default_node }}`
as our default version. If you'd like a specific version, then you can specify it in your circle.yml:

```
machine:
  node:
    version: 0.10.22
```

#### node 0.12

Until containers are updated (coming soon!) node v0.12.x requires a source compilation that takes ~10 minutes.  If you would like to reduce this significantly (~5 seconds), add the following to your circle.yml:

```
machine:
  pre:
    - curl https://raw.githubusercontent.com/creationix/nvm/v0.23.3/install.sh | bash
  node:
    version: 0.12
```

### Dependencies

If Circle finds a `package.json`, we automatically run `npm install` to fetch
all of your project's dependencies.
If needed, you can add custom dependencies commands from your circle.yml.
For example, you can override our default command to pass a special flag to `npm`:

```
dependencies:
  override:
    - npm install --dev
```

### Databases

We have pre-installed more than a dozen [databases and queues](/docs/environment#databases),
including PostgreSQL and MySQL. If needed, you can
[manually set up your test database](/docs/manually#dependencies) from your circle.yml.

### Testing

Circle will run `npm test` when you specify a test script in `package.json`.
We also run your Mocha tests as well as run any `test` targets in Cakefiles or Makefiles.

You can [add additional test commands](/docs/configuration#test)
from your circle.yml. For example, you could run a custom `test.sh` script:

```
test:
  post:
    - ./test.sh
```

### Deployment

Circle offers first-class support for [deployment](/docs/configuration#deployment).
When a build is green, Circle will deploy your project as directed
in your `circle.yml` file.
We can deploy to Nodejitsu and other PaaSes as well as to
physical servers under your control.

If you have any trouble, please [contact us](mailto:sayhi@circleci.com)
and we will be happy to help.
