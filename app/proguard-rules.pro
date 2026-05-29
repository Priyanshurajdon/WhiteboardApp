# Apache POI - keep all classes
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class schemasMicrosoftComOfficeOffice.** { *; }
-keep class schemasMicrosoftComOfficeWord.** { *; }
-keep class schemasMicrosoftComVml.** { *; }

# Keep our drawing code
-keep class com.whiteboard.app.** { *; }

# General Android rules
-keepattributes Signature
-keepattributes *Annotation*
