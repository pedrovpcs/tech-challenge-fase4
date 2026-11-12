#!/usr/bin/env sh
set -eu
mvn -B verify
sam validate --lint --region "${AWS_REGION:-us-east-2}"
