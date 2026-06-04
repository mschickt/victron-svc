package de.mhome.victron.control;

import jakarta.enterprise.context.ApplicationScoped;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@ApplicationScoped
public class AesCtrDecryptor {

    /**
     * Entschlüsselt Victron BLE Payload.
     *
     * @param encryptedData     verschlüsselte Bytes (ab Byte 3 der Manufacturer Data)
     * @param nonce             16-Bit Nonce (Bytes 1-2 der Manufacturer Data, little-endian)
     * @param advertisementKey  Hex-String aus VictronConnect App
     */
    public byte[] decrypt(byte[] encryptedData, int nonce, String advertisementKey) {
        try {
            byte[] keyBytes = hexToBytes(advertisementKey);

            // IV = nonce (2 Bytes LE) + 14 zero-Bytes
            byte[] iv = new byte[16];
            iv[0] = (byte) (nonce & 0xFF);
            iv[1] = (byte) ((nonce >> 8) & 0xFF);
            // [2..15] bleiben 0x00

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(encryptedData);

        } catch (Exception e) {
            throw new VictronDecryptionException("AES-CTR Entschlüsselung fehlgeschlagen", e);
        }
    }

    private byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static class VictronDecryptionException extends RuntimeException {
        public VictronDecryptionException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
