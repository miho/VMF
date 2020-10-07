#!/bin/bash

export revision=`cat ../config/common.properties | grep publication.version | cut -d'=' -f2`

mvn -Drevision=${revision} "$@"
