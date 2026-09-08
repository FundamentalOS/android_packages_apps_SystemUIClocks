# Open-source Pixel-style lockscreen clock-face plugins (caimito). The apks are Soong modules
# (Android.bp); this only pulls them into the product. Plugin loading on user builds is enabled
# via config_pluginAllowlist in vendor/fundamental/overlay/no-rro.

PRODUCT_PACKAGES += \
    SystemUIClocks-BigNum \
    SystemUIClocks-Calligraphy \
    SystemUIClocks-Growth \
    SystemUIClocks-Inflate \
    SystemUIClocks-Metro \
    SystemUIClocks-NumOverlap \
    SystemUIClocks-Weather \
    SystemUIClocks-Words
