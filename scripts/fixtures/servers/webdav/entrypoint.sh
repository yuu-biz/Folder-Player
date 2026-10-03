#!/bin/sh
set -e
mkdir -p /srv/dav /usr/local/apache2/var
cp -a /fixture/. /srv/dav/
mkdir -p /srv/dav/rw
U=$(awk '/^User /{print $2}' /usr/local/apache2/conf/httpd.conf); G=$(awk '/^Group /{print $2}' /usr/local/apache2/conf/httpd.conf)
chown -R "$U:$G" /srv/dav /usr/local/apache2/var
exec httpd-foreground
