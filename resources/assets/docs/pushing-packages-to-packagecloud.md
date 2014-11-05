<!--

title: Pushing packages to packagecloud
last_updated: November 4, 2014

-->

[packagecloud](https://packagecloud.io) is a hosted package repository service. It allows users to host apt, yum and rubygem repositories without any pre-configuration.

## Pushing Packages

To push packages to packagecloud from CircleCI you must:

  - Set an environment variable named `PACKAGECLOUD_TOKEN` on the **Project settings > Environment variables** page.  The value must match your packagecloud API access token. Visit your [API token settings page](https://packagecloud.io/api_token) on packagecloud to get your API token.
  - Create a [circle.yml](/docs/configuration) file which installs the package_cloud gem and pushes the package to the [OS and version of your choice](https://packagecloud.io/docs#os_distro_version).
For more info on your API access token, please refer to the API Tokens section in [API docs](https://packagecloud.io/docs/api#api_tokens).

```
dependencies:
  pre:
    - gem install package_cloud

deployment:
  production:
    branch: master
    commands:
      - package_cloud push user/repo/os/version ./path/to/package.ext
```

For more information on pushing packages to packagecloud, please visit [packagecloud.io](https://packagecloud.io).
