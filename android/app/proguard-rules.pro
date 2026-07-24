# Garmin FIT SDK строит сообщения через фабрику и рефлексию по полям —
# без этого декодер молча не находит типы сообщений.
-keep class com.garmin.fit.** { *; }
-dontwarn com.garmin.fit.**

# Health Connect ходит по protobuf-моделям рефлексией.
-keep class androidx.health.** { *; }
-dontwarn androidx.health.**
