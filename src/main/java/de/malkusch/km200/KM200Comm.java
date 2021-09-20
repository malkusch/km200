package de.malkusch.km200;

import static java.util.Base64.getEncoder;
import static java.util.Base64.getMimeDecoder;

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

/**
 * This class was taken from the OpenHAB 1.x Buderus / KM200 binding, and
 * modified to run without the OpenHAB infrastructure. Not needed code was
 * removed.
 *
 * The KM200Comm class does the communication to the device and does any
 * encryption/decryption/converting jobs
 *
 * @author Markus Eckhardt
 * @since 1.9.0
 */

final class KM200Comm {

    /**
     * This function removes zero padding from a byte array.
     */
    public static byte[] removeZeroPadding(byte[] bytes) {
        int i = bytes.length - 1;
        while (i >= 0 && bytes[i] == 0) {
            --i;
        }
        return Arrays.copyOf(bytes, i + 1);
    }

    /**
     * This function adds zero padding to a byte array.
     */
    public static byte[] addZeroPadding(byte[] bdata, int bSize, String cSet) throws UnsupportedEncodingException {
        int encrypt_padchar = bSize - (bdata.length % bSize);
        byte[] padchars = new String(new char[encrypt_padchar]).getBytes(cSet);
        byte[] padded_data = new byte[bdata.length + padchars.length];
        System.arraycopy(bdata, 0, padded_data, 0, bdata.length);
        System.arraycopy(padchars, 0, padded_data, bdata.length, padchars.length);
        return padded_data;
    }

    /**
     * This function does the decoding for a new message from the device
     */
    public String decodeMessage(KM200Device device, byte[] encoded) {
        String retString = null;
        byte[] decodedB64 = null;

        try {
            decodedB64 = getMimeDecoder().decode(encoded);
        } catch (Exception e) {
            throw new KM200Exception("Message is not in valid Base64 scheme", e);
        }
        try {
            /* Check whether the length of the decryptData is NOT multiplies of 16 */
            if ((decodedB64.length & 0xF) != 0) {
                /* Return the data */
                retString = new String(decodedB64, device.getCharSet());
                return retString;
            }
            // --- create cipher
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(device.getCryptKeyPriv(), "AES"));
            final byte[] decryptedData = cipher.doFinal(decodedB64);
            byte[] decryptedDataWOZP = removeZeroPadding(decryptedData);
            retString = new String(decryptedDataWOZP, device.getCharSet());
            return retString;
        } catch (BadPaddingException | IllegalBlockSizeException | UnsupportedEncodingException
                | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
            // failure to authenticate
            throw new KM200Exception("Exception on encoding", e);
        }
    }

    public byte[] encodeMessage(KM200Device device, String data) {
        byte[] encryptedDataB64 = null;

        try {
            // --- create cipher
            byte[] bdata = data.getBytes(device.getCharSet());
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(device.getCryptKeyPriv(), "AES"));
            int bsize = cipher.getBlockSize();
            final byte[] encryptedData = cipher.doFinal(addZeroPadding(bdata, bsize, device.getCharSet()));
            try {
                encryptedDataB64 = getEncoder().encode(encryptedData);
            } catch (Exception e) {
            }
            return encryptedDataB64;
        } catch (UnsupportedEncodingException | GeneralSecurityException e) {
            // failure to authenticate
            throw new KM200Exception("Exception on encoding", e);
        }
    }
}