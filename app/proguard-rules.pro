# EventBus
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson
-keep class org.fossify.commons.models.SimpleContact { *; }
-keep class com.glooboo.umessage.models.Attachment { *; }
-keep class com.glooboo.umessage.models.MessageAttachment { *; }

# ez-vcard's parameter registries use reflection to enumerate public constants
# and create runtime values for unknown TYPE/MEDIATYPE parameters.
-keepclassmembers,allowobfuscation class ezvcard.parameter.* extends ezvcard.parameter.VCardParameter {
    public static final <fields>;
    <init>(java.lang.String);
}

-keepclassmembers,allowobfuscation class ezvcard.parameter.* extends ezvcard.parameter.MediaTypeParameter {
    <init>(java.lang.String, java.lang.String, java.lang.String);
}

# Release builds drop the chatty log calls and stack-trace printing (S17): a stack trace of a provider or database
# error can quote a query or an address, and a bug report or adb log would carry it. Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
