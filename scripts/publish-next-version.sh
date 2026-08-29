#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_properties_file="${project_dir}/local.properties"

read_local_property() {
    local property_name="$1"
    if [[ ! -f "${local_properties_file}" ]]; then
        return 0
    fi
    awk -F= -v property_name="${property_name}" '
        $1 == property_name {
            sub(/^[^=]*=/, "")
            print
            exit
        }
    ' "${local_properties_file}"
}

packages_username="${GITHUB_PACKAGES_USERNAME:-}"
packages_token="${GITHUB_PACKAGES_TOKEN:-}"

if [[ -z "${packages_username}" ]]; then
    packages_username="$(read_local_property github.packages.username)"
fi
if [[ -z "${packages_token}" ]]; then
    packages_token="$(read_local_property github.packages.token)"
fi

if [[ -z "${packages_username}" || -z "${packages_token}" ]]; then
    echo "Missing GitHub Packages credentials." >&2
    echo "Set GITHUB_PACKAGES_USERNAME/GITHUB_PACKAGES_TOKEN or local.properties keys." >&2
    exit 1
fi

temporary_dir="$(mktemp -d)"
trap 'rm -rf "${temporary_dir}"' EXIT

credentials_file="${temporary_dir}/netrc"
metadata_file="${temporary_dir}/maven-metadata.xml"
chmod 700 "${temporary_dir}"
printf 'machine maven.pkg.github.com\nlogin %s\npassword %s\n' \
    "${packages_username}" "${packages_token}" > "${credentials_file}"
chmod 600 "${credentials_file}"

metadata_url="https://maven.pkg.github.com/vihuela/ads-mediation/com/cashcraft/ads-mediation/maven-metadata.xml"
http_status="$(curl --silent --show-error --netrc-file "${credentials_file}" \
    --output "${metadata_file}" --write-out '%{http_code}' "${metadata_url}")"

latest_version=""
if [[ "${http_status}" == "200" ]]; then
    latest_version="$(
        tr '<' '\n' < "${metadata_file}" |
            sed -n 's#^version>\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$#\1#p' |
            awk -F. '
                BEGIN { major = -1; minor = -1; patch = -1 }
                $1 > major || ($1 == major && $2 > minor) ||
                    ($1 == major && $2 == minor && $3 > patch) {
                    major = $1
                    minor = $2
                    patch = $3
                }
                END {
                    if (major >= 0) print major "." minor "." patch
                }
            '
    )"
elif [[ "${http_status}" != "404" ]]; then
    echo "Unable to read GitHub Packages metadata (HTTP ${http_status})." >&2
    exit 1
fi

if [[ -n "${VERSION_NAME:-}" ]]; then
    next_version="${VERSION_NAME}"
elif [[ -z "${latest_version}" ]]; then
    next_version="1.0.0"
else
    IFS=. read -r version_major version_minor version_patch <<< "${latest_version}"
    next_version="${version_major}.${version_minor}.$((version_patch + 1))"
fi

echo "Current package version: ${latest_version:-none}"
echo "Next package version: ${next_version}"

if [[ "${1:-}" == "--print-next-version" ]]; then
    exit 0
fi

cd "${project_dir}"
./gradlew \
    testDebugUnitTest \
    lintDebug \
    :r8-smoke-app:assembleRelease \
    publishReleasePublicationToGitHubPackagesRepository \
    -PVERSION_NAME="${next_version}"
