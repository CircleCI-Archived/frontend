<!--

title: Collecting test metadata
last_updated: Feb 17, 2015

-->

CircleCI can collect test metadata from JUnit XML files and Cucumber JSON files.
We'll use the test metadata to give you better insight into your build. For our
inferred steps that use parallelism, we'll use the timing information to get you
better test splits and finish your builds faster.

## Automatic test metadata collection

If you're using our inferred test steps for Ruby or Python then we'll
automatically collect test metadata, though for RSpec, Minitest, and Django
you'll need to do some configuration to to enable the formatters:

For RSpec:

Add this to your gemfile:
```
gem 'rspec_junit_formatter', '0.2.2'
```

For Minitest:

Add this to your gemfile:
```
gem 'minitest-ci', :git => 'git@github.com:circleci/minitest-ci.git'
```

For Django:

Configure your tests to run using the
[django-nose](https://github.com/django-nose/django-nose) test runner.  We'll
do the rest automatically.

Or if you'd rather use another django test runner, and it's capable of
producing XUnit XML files, configure it to output them to the
`$CIRCLE_TEST_REPORTS/django` directory (you'll have to create the django
directory if your runner doesn't create the destination directory
automatically.)  We'll still auto-parallelize your tests, and incorporate the
timing information.

## Metadata collection in custom test steps

If you have a custom test step that produces JUnit XML output - most test runners support this in some form - you can write the XML
files to the `$CIRCLE_TEST_REPORTS` directory.  We'll automatically store the files in your
[build artifacts](/docs/build-artifacts) and parse the XML.

You can tell us the type of test by putting the files in a subdirectory of `$CIRCLE_TEST_REPORTS`.
For example, if you have RSpec tests, you would write your XML files to `$CIRCLE_TEST_REPORTS/rspec`.

### Cucumber

For custom Cucumber steps, you should generate a file using the JSON formatter that ends
with `.cucumber` and write it to the `$CIRCLE_TEST_REPORTS/cucumber` directory.  Your [circle.yml](/docs/configuration) might be:

```
test:
  override:
    - mkdir -p $CIRCLE_TEST_REPORTS/cucumber
    - bundle exec cucumber --format json --out $CIRCLE_TEST_REPORTS/cucumber/tests.cucumber
```


### PHPUnit

For PHPUnit tests, you should generate a file using the `--log-junit` comment line option and write it to the `$CIRCLE_TEST_REPORTS/phpunit` directory.  Your [circle.yml](/docs/configuration) might be:

```
test:
  override:
    - mkdir -p $CIRCLE_TEST_REPORTS/phpunit
    - phpunit --log-junit $CIRCLE_TEST_REPORTS/phpunit/junit.xml tests
```

### RSpec

To add test metadata collection to a project that uses a custom `rspec` build step, add the following gem to your Gemfile:

```
gem 'rspec_junit_formatter', '0.2.2'
```

And modify your test command to this:

````
test:
  override:
    - RAILS_ENV=test bundle exec rspec -r rspec_junit_formatter --format RspecJunitFormatter -o $CIRCLE_TEST_REPORTS/rspec/junit.xml
````

### Java JUnit results with Maven Surefire Plugin

If you are building a [Gradle](https://gradle.org/) or
[Maven](http://maven.apache.org/) based project, you are more than likely using
the [Maven Surefire plugin](http://maven.apache.org/surefire/maven-surefire-plugin/)
to generate test reports in XML format. CircleCI makes it easy to collect these
reports. You just need to add the followng to the `circle.yml` file in your
project.

```
test:
  post:
    - mkdir -p $CIRCLE_TEST_REPORTS/junit/
    - find . -type f -regex ".*/target/surefire-reports/.*xml" -exec cp {} $CIRCLE_TEST_REPORTS/junit/ \;
```

### test2junit for Clojure tests
You can use [test2junit](https://github.com/ruedigergad/test2junit) to convert Clojure test output to XML format. For more details, please checkout our [sample project](https://github.com/kimh/circleci-build-recipies).

## API

You can access test metadata for a build from the [API](/docs/api#test-metadata).
