#!/bin/zsh
# Asks for a new TMDB key or v4 token (hidden while pasted), writes it to launcher/local.properties (gitignored) and
# deploys the Worker with it (deploy.sh). The key is never printed.
set -e
cd "$(dirname "$0")/../.."
read -rs "T?Вставте токен TMDB і натисніть Enter: "
echo
T=$(printf '%s' "$T" | tr -d '[:space:]')
[ -n "$T" ] || { echo "Порожньо, нічого не змінено." >&2; exit 1; }
sed -i '' '/^tmdb\.key=/d' launcher/local.properties
printf 'tmdb.key=%s\n' "$T" >> launcher/local.properties
unset T
echo "Ключ збережено, розгортаю Worker..."
tools/tmdb-proxy/deploy.sh
echo "Готово."
