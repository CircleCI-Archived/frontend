<!--

title: CI for Embedded Platforms

-->

## Overview

[PlatformIO](http://platformio.org/) is a cross-platform code builder and the
missing library manager for embedded development. PlatformIO is written in
pure Python and doesn't depend on any additional libraries/toolchains from an
operation system. Thanks to it, you can test your embedded projects on the
multiple operating systems.

## Supported Embedded Platforms, Frameworks and Boards

### Embedded Platforms

PlatformIO has pre-built different development platforms for the most popular
host OS (Mac OS X, Windows, Linux 32/64bit, Linux ARMv6+). Each of them
includes compiler, debugger, uploader and many other useful tools.

<ul>
  <li>Atmel AVR</li>
  <li>Atmel SAM</li>
  <li>Espressif</li>
  <li>Freescale Kinetis</li>
  <li>Nordic nRF51</li>
  <li>NXP LPC</li>
  <li>Silicon Labs EFM32</li>
  <li>ST STM32</li>
  <li>Teensy</li>
  <li>TI MSP430</li>
  <li>TI TIVA</li>
</ul>

For the actual list, please follow to
[PlatformIO Development Platforms](http://platformio.org/#!/platforms).

### Embedded Frameworks

PlatformIO has pre-configured build scripts for the popular embedded frameworks.

<ul>
  <li>Arduino</li>
  <li>CMSIS</li>
  <li>libOpenCM3</li>
  <li>Energia</li>
  <li>SPL</li>
  <li>mbed</li>
</ul>

For the actual list, please follow to
[PlatformIO Frameworks](http://platformio.org/#!/frameworks).

### Embedded Boards

PlatformIO has pre-defined compilation profiles for a variety of embedded
boards. For more details, please follow to
[PlatformIO Embedded Boards Explorer](http://platformio.org/#!/boards).

## Setting `circle.yml`

Please make sure to read official
[PlatformIO & Circle CI](http://docs.platformio.org/en/latest/ci/circleci.html) documentation first.

```
dependencies:
    pre:
        # Install the latest stable PlatformIO
        - sudo pip install -U platformio

test:
    override:
        - platformio ci path/to/test/file.c --board=TYPE_1 --board=TYPE_2 --board=TYPE_N
        - platformio ci examples/file.ino --board=TYPE_1 --board=TYPE_2 --board=TYPE_N
        - platformio ci path/to/test/directory --board=TYPE_1 --board=TYPE_2 --board=TYPE_N
```

For the board types please go to [Embedded Boards](#Embedded-Boards) section.

### Project as a library

When project is written as a library (where own examples or testing code use
it), please use `--lib="."` option for [platformio ci](http://docs.platformio.org/en/latest/userguide/cmd_ci.html#cmdoption-platformio-ci-l) command

```
test:
    override:
        - platformio ci path/to/test/file.c --lib="." --board=TYPE_1 --board=TYPE_2 --board=TYPE_N
```

### Library dependecies

There 2 options to test source code with dependent libraries:

#### Install dependent library using [PlatformIO Library Manager](http://platformio.org/#!/lib)

```
dependencies:
    pre:
        # Install the latest stable PlatformIO
        - sudo pip install -U platformio

        # OneWire Library with ID=1 http://platformio.org/#!/lib/show/1/OneWire
        - platformio lib install 1

test:
    override:
        - platformio ci path/to/test/file.c --board=TYPE_1 --board=TYPE_2 --board=TYPE_N
```

#### Manually download dependent library and include in build process via `--lib` option

```
dependencies:
    pre:
        # Install the latest stable PlatformIO
        - sudo pip install -U platformio

        # download library to the temporary directory
        - wget https://github.com/PaulStoffregen/OneWire/archive/master.zip -O /tmp/onewire_source.zip
        - unzip /tmp/onewire_source.zip -d /tmp/

test:
    override:
        - platformio ci path/to/test/file.c --lib="/tmp/OneWire-master" --board=TYPE_1 --board=TYPE_2 --board=TYPE_N
```

### Custom Build Flags

PlatformIO allows to specify own build flags using
[PLATFORMIO_BUILD_FLAGS](http://docs.platformio.org/en/latest/envvars.html#envvar-PLATFORMIO_BUILD_FLAGS) environment

```
machine:
    environment:
        PLATFORMIO_BUILD_FLAGS: -D SPECIFIC_MACROS -I/extra/inc
```

For the more details, please follow to [available build flags/options](http://docs.platformio.org/en/latest/projectconf.html#build-flags).


### Advanced configuration

PlatformIO allows to configure multiple build environments for the single
source code using Project Configuration File [platformio.ini](http://docs.platformio.org/en/latest/projectconf.html).

Instead of `--board` option, please use [platformio ci --project-conf](http://docs.platformio.org/en/latest/userguide/cmd_ci.html#cmdoption-platformio-ci--project-conf).

```
test:
    override:
        - platformio ci path/to/test/file.c --project-conf=/path/to/platoformio.ini
```

## Examples `circle.yml`

- [Cache development platforms](https://github.com/ivankravets/USB_Host_Shield_2.0/blob/master/circle.yml)
- [Custom build flags](https://github.com/ivankravets/Arduino-IRremote/blob/master/circle.yml)
- [Dependency on external libraries](https://github.com/ivankravets/ethercard/blob/master/circle.yml)
- [Test with multiple desktop platforms](https://github.com/smartanthill/commstack-server/blob/develop/circle.yml)
