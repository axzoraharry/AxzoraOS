#!/usr/bin/env bash
# Rebrand a synced LineageOS 23.0 tree as "Axzora OS".
# Run INSIDE the AxzoraBuild WSL distro AFTER `repo sync` completes:
#   bash /android/rebrand-axzora.sh
#
# Idempotent: safe to re-run after `repo sync` refreshes the tree.
set -euo pipefail

TREE=/android/lineage
OS_NAME="Axzora"
OS_VERSION="1.0"

cd "$TREE"

echo "==> [1/4] Product branding props"
BRANDING_MK="vendor/lineage/config/branding.mk"
if [ -f "$BRANDING_MK" ]; then
  # Version string the OS reports (Settings -> About phone)
  sed -i "s/^LINEAGE_VERSION := .*/LINEAGE_VERSION := ${OS_NAME}-${OS_VERSION}-\$(shell date -u +%Y%m%d)-\$(LINEAGE_BUILDTYPE)-\$(LINEAGE_BUILD)/" "$BRANDING_MK" || true
fi

echo "==> [2/4] User-visible 'LineageOS' strings in Settings & SetupWizard"
# Replace the display name in translatable strings only — never in code,
# package names, paths, or URLs, which would break the build.
for f in \
  vendor/lineage/overlay/common/frameworks/base/packages/SettingsProvider/res/values/defaults.xml \
  packages/apps/Settings/res/values/strings.xml
do
  [ -f "$f" ] && sed -i "s/LineageOS/${OS_NAME} OS/g; s/Lineage OS/${OS_NAME} OS/g" "$f" || true
done
# Every locale of the Lineage settings strings that says "LineageOS"
if [ -d vendor/lineage/overlay ]; then
  grep -rl --include='strings.xml' 'LineageOS' vendor/lineage/overlay 2>/dev/null \
    | xargs -r sed -i "s/LineageOS/${OS_NAME} OS/g"
fi

echo "==> [3/4] Custom ro.axzora.* build props"
EXTRA_MK="vendor/lineage/config/common.mk"
if [ -f "$EXTRA_MK" ] && ! grep -q "ro.axzora.version" "$EXTRA_MK"; then
  cat >> "$EXTRA_MK" <<EOF

# Axzora OS identity
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \\
    ro.axzora.version=${OS_VERSION} \\
    ro.axzora.name=${OS_NAME}
EOF
fi

echo "==> [4/4] Bundle Axzora launcher + Axa Store as system priv-apps"
PREBUILT_DIR="vendor/axzora/prebuilt"
mkdir -p "$PREBUILT_DIR"
cat > vendor/axzora/axzora.mk <<'EOF'
# Axzora OS additions
PRODUCT_PACKAGES += \
    AxzoraLauncher \
    AxaStore
EOF
echo "    (drop AxzoraLauncher.apk / AxaStore.apk into $PREBUILT_DIR"
echo "     with matching Android.mk before the final build — see README)"

echo
echo "Rebrand complete. Build with:"
echo "  source build/envsetup.sh && breakfast a53x && brunch a53x"
