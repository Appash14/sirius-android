#!/system/bin/sh
# CA éphémère dans les namespaces des futurs processus d'application API 34.
# Méthode : https://httptoolkit.com/blog/android-14-install-system-ca-certificate/
set -eu
certificate=${1:?Chemin du certificat requis}
backup=/data/local/tmp/sirius-e2e-cacerts
system=/system/etc/security/cacerts
apex=/apex/com.android.conscrypt/cacerts
mkdir -p "$backup"
cp "$apex"/* "$backup/"
mount -t tmpfs tmpfs "$system"
cp "$backup"/* "$system/"
cp "$certificate" "$system/"
chown root:root "$system"/*
chmod 644 "$system"/*
chcon u:object_r:system_file:s0 "$system" "$system"/*
zygotes="$(pidof zygote64 || true) $(pidof zygote || true)"
test -n "$(echo "$zygotes" | tr -d ' ')"
for process in $zygotes; do
  nsenter --mount="/proc/$process/ns/mnt" -- /system/bin/mount --bind "$system" "$apex"
done
echo 'CA Sirius E2E installée pour les nouvelles applications'
