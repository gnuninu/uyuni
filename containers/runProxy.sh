#!/bin/bash

set -x
CURDIR=$(dirname $(realpath $0))

IMAGE=proxy
CONFIG_DIR="$CURDIR/proxy-config"


docker run --rm=true -ti \
	-e UYUNI_MASTER='suma-refhead-srv.mgr.suse.de' \
	-e UYUNI_ACTIVATION_KEY='1-proxy' \
	-e UYUNI_MINION_ID='mc-proxy.suse.de' \
	-e UYUNI_MACHINE_ID='488de1bd7b08472cba12c6e3c775d4bb' \
	-v $CONFIG_DIR:/config \
	--name uyuni_proxy \
        $IMAGE

