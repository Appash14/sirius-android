#!/usr/bin/env bash
set -euo pipefail
destination=${1:?Dossier des certificats requis}
mkdir -p "$destination"
umask 077
cat > "$destination/ca.cnf" <<'EOF'
[req]
distinguished_name=dn
x509_extensions=ca
prompt=no
[dn]
CN=Sirius E2E CA
[ca]
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
EOF
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -sha256 \
  -config "$destination/ca.cnf" \
  -keyout "$destination/ca.key" -out "$destination/ca.pem" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -subj '/CN=10.0.2.2' \
  -keyout "$destination/server.key" -out "$destination/server.csr" 2>/dev/null
cat > "$destination/server.ext" <<'EOF'
subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EOF
openssl x509 -req -in "$destination/server.csr" -CA "$destination/ca.pem" \
  -CAkey "$destination/ca.key" -CAcreateserial -days 2 -sha256 \
  -extfile "$destination/server.ext" -out "$destination/server.pem" 2>/dev/null
