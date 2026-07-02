package com.musicplayer.player;

import com.musicplayer.DebugLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Decodes OGG Vorbis to PCM using JCraft JOrbis (via reflection).
 * Returns PCM data with explicit format parameters (no javax.sound.sampled.AudioFormat).
 */
public class FullPcmDecoder {
   public FullPcmDecoder() {
   }

   public static FullPcmDecoder.Result decodeSeeked(Identifier oggResId, float seconds) {
      try {
         Optional<Resource> optional = Minecraft.getInstance().getResourceManager().getResource(oggResId);
         if (optional.isEmpty()) {
            return null;
         } else {
            byte[] abyte;
            try (InputStream inputstream = optional.get().open()) {
               abyte = inputstream.readAllBytes();
            }
            return decodeBytesSeeked(abyte, seconds);
         }
      } catch (Exception exception) {
         DebugLog.info("FullPcmDecoder: %s", exception.getMessage());
         return null;
      }
   }

   private static FullPcmDecoder.Result decodeBytesSeeked(byte[] raw, float seconds) {
      try {
         Class<?> cls = Class.forName("com.jcraft.jorbis.VorbisFile");
         Object vf = cls.getConstructor(InputStream.class, byte[].class, int.class).newInstance(new ByteArrayInputStream(raw), null, 0);
         Method getInfo = cls.getMethod("getInfo", int.class);
         Object info = getInfo.invoke(vf, -1);
         int sampleRate = info.getClass().getField("rate").getInt(info);
         int channels = info.getClass().getField("channels").getInt(info);
         long seekSample = (long)(seconds * (float)sampleRate + 0.5F);
         Method pcmSeek = cls.getMethod("pcmSeek", long.class);
         pcmSeek.invoke(vf, seekSample);

         Method readMethod = findReadMethod(cls);
         if (readMethod == null) {
            close(vf, cls);
            return null;
         } else {
            readMethod.setAccessible(true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int[] dummy = new int[1];

            while (true) {
               int read = (Integer)readMethod.invoke(vf, buffer, buffer.length, 0, 2, 1, dummy);
               if (read <= 0) {
                  close(vf, cls);
                  byte[] pcmBytes = baos.toByteArray();
                  ByteBuffer pcmBuffer = ByteBuffer.allocateDirect(pcmBytes.length);
                  pcmBuffer.order(ByteOrder.LITTLE_ENDIAN);
                  pcmBuffer.put(pcmBytes);
                  pcmBuffer.flip();
                  return new FullPcmDecoder.Result(pcmBuffer, sampleRate, channels);
               }
               baos.write(buffer, 0, read);
            }
         }
      } catch (Exception exception) {
         DebugLog.info("FullPcmDecoder.decodeBytesSeeked: %s", exception.getMessage());
         return null;
      }
   }

   private static Method findReadMethod(Class<?> cls) {
      for (Method method : cls.getDeclaredMethods()) {
         if (method.getName().equals("read") && method.getParameterCount() == 6) {
            Class<?>[] ptypes = method.getParameterTypes();
            if (ptypes[0] == byte[].class
               && ptypes[1] == int.class
               && ptypes[2] == int.class
               && ptypes[3] == int.class
               && ptypes[4] == int.class
               && ptypes[5] == int[].class) {
               return method;
            }
         }
      }
      return null;
   }

   private static void close(Object vf, Class<?> cls) {
      try {
         cls.getMethod("close").invoke(vf);
      } catch (Exception exception) {
      }
   }

   /**
    * Result record containing PCM data and format parameters.
    * Replaces the old AudioFormat-based record for OpenAL compatibility.
    */
   public static record Result(ByteBuffer pcmData, int sampleRate, int channels) {
   }
}
