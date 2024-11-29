#!/bin/bash
sbt -batch -java-home /usr/lib/jvm/jdk-24 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff jdk-24-t16.json .*' 2>&1 | tee jdk-24-t16.txt
sbt -batch -java-home /usr/lib/jvm/jdk-21 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff jdk-21-t16.json .*' 2>&1 | tee jdk-21-t16.txt
sbt -batch -java-home /usr/lib/jvm/jdk-17 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff jdk-17-t16.json .*' 2>&1 | tee jdk-17-t16.txt
sbt -batch -java-home /usr/lib/jvm/graalvm-jdk-24 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff graalvm-jdk-24-t16.json .*' 2>&1 | tee graalvm-jdk-24-t16.txt
sbt -batch -java-home /usr/lib/jvm/graalvm-jdk-21 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff graalvm-jdk-21-t16.json .*' 2>&1 | tee graalvm-jdk-21-t16.txt
sbt -batch -java-home /usr/lib/jvm/graalvm-jdk-17 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff graalvm-jdk-17-t16.json .*' 2>&1 | tee graalvm-jdk-17-t16.txt
sbt -batch -java-home /usr/lib/jvm/graalvm-community-jdk-24 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff graalvm-community-jdk-24-t16.json .*' 2>&1 | tee graalvm-community-jdk-24-t16.txt
sbt -batch -java-home /usr/lib/jvm/graalvm-community-jdk-21 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff graalvm-community-jdk-21-t16.json .*' 2>&1 | tee graalvm-community-jdk-21-t16.txt
sbt -batch -java-home /usr/lib/jvm/graalvm-community-jdk-17 ++3.6.2-RC3 clean 'jsoniter-scala-benchmarkJVM/jmh:run -jvmArgsAppend "-Djmh.executor=FJP" -t 16 -p size=128 -prof gc -rf json -rff graalvm-community-jdk-17-t16.json .*' 2>&1 | tee graalvm-community-jdk-17-t16.txt
