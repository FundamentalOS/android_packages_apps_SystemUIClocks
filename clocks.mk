# Pixel lockscreen clock-face plugins (caimito), imported presigned from the stock BP4A image.
# The APKs are Soong modules (Android.bp); this only pulls them into the product. User-build
# plugin loading is enabled via config_pluginAllowlist in vendor/fundamental/overlay/common.
# Device-bound: inherited from device/google/caimito/device-<codename>.mk.

PRODUCT_PACKAGES += \
    SystemUIClocks-BigNum \
    SystemUIClocks-Calligraphy \
    SystemUIClocks-Growth \
    SystemUIClocks-Inflate \
    SystemUIClocks-Metro \
    SystemUIClocks-NumOverlap \
    SystemUIClocks-Weather
