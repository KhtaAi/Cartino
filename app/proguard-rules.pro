# Keep data models used with Room, Moshi, and ViewModel
-keep class com.example.data.model.** { *; }
-keep class com.example.data.model.PasswordGroup { *; }
-keep class com.example.data.model.PasswordEntry { *; }
-keep class com.example.data.model.CustomPasswordField { *; }
-keep class com.example.data.model.GeneratedPasswordHistory { *; }
-keep class com.example.util.BackupPayload { *; }
-keep class com.example.util.WebDavConfig { *; }
-keep class com.example.util.CustomDocField { *; }

# Keep Room generated classes and TypeConverters
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep Moshi JSON adapters
-keep class com.squareup.moshi.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Android Security Crypto
-keep class androidx.security.crypto.** { *; }
