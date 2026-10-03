#!/bin/sh
set -e
for u in alice bob; do
  id "$u" >/dev/null 2>&1 || adduser -D -H "$u"
done
printf 'alicepass\nalicepass\n' | smbpasswd -s -a alice >/dev/null
printf 'bobpass\nbobpass\n' | smbpasswd -s -a bob >/dev/null
mkdir -p /srv/music /srv/home/alice/Album /srv/home/bob/Album /srv/alice /srv/public
cp -a /fixture/. /srv/music/
mkdir -p /srv/music/rw /srv/music/Locked
cp /srv/music/fixture/Album-B/track.flac /srv/music/Locked/secret.flac
chown -R alice /srv/music/rw
chown root:root /srv/music/Locked && chmod 0700 /srv/music/Locked
cp /fixture/fixture/Album-A/cover.jpg /srv/home/alice/Album/cover.jpg
cp /fixture/fixture/Album-A/folder.png /srv/home/bob/Album/cover.png
echo alice > /srv/home/alice/Album/owner.txt; echo bob > /srv/home/bob/Album/owner.txt
cp /fixture/fixture/Album-B/track.flac /srv/home/alice/Album/track.flac
cp /fixture/fixture/Album-B/track.flac /srv/home/bob/Album/track.flac
chown -R alice /srv/home/alice; chown -R bob /srv/home/bob
echo "alice only" > /srv/alice/readme.txt
cp -a /fixture/fixture/Album-B /srv/public/
exec smbd --foreground --no-process-group --debug-stdout
