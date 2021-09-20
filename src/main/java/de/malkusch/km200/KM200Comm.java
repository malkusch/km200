package de.malkusch.km200;

/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */

import java.io.IOException;
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

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

import lombok.RequiredArgsConstructor;

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

@RequiredArgsConstructor
final class KM200Comm {

    private static final Logger logger = LoggerFactory.getLogger(KM200Comm.class);
    private final HttpClient client;

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
     * This function does the GET http communication to the device
     */
    public byte[] getDataFromService(KM200Device device, String service) {
        byte[] responseBodyB64 = null;

        // Create a method instance.
        GetMethod method = new GetMethod("http://" + device.getIP4Address() + service);

        // Provide custom retry handler is necessary
        method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler(3, false));
        // Set the right header
        method.setRequestHeader("Accept", "application/json");
        method.addRequestHeader("User-Agent", "TeleHeater/2.2.3");

        try {
            // Execute the method.
            int statusCode = client.executeMethod(method);
            // Check the status and the forbidden 403 Error.
            if (statusCode != HttpStatus.SC_OK) {
                String statusLine = method.getStatusLine().toString();
                if (statusLine.contains(" 403 ")) {
                    return new byte[1];
                } else {
                    logger.error("HTTP GET failed: {}", method.getStatusLine());
                    return null;
                }
            }
            device.setCharSet(method.getResponseCharSet());
            // Read the response body.
            responseBodyB64 = ByteStreams.toByteArray(method.getResponseBodyAsStream());

        } catch (HttpException e) {
            logger.error("Fatal protocol violation: ", e);
        } catch (IOException e) {
            logger.error("Fatal transport error: ", e);
        } finally {
            // Release the connection.
            method.releaseConnection();
        }
        return responseBodyB64;
    }

    /**
     * This function does the SEND http communication to the device
     *
     */
    public Integer sendDataToService(KM200Device device, String service, byte[] data) {
        // Create an instance of HttpClient.
        Integer rCode = null;
        synchronized (client) {

            // Create a method instance.
            PostMethod method = new PostMethod("http://" + device.getIP4Address() + service);

            // Provide custom retry handler is necessary
            method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,
                    new DefaultHttpMethodRetryHandler(3, false));
            // Set the right header
            method.setRequestHeader("Accept", "application/json");
            method.addRequestHeader("User-Agent", "TeleHeater/2.2.3");
            method.setRequestEntity(new ByteArrayRequestEntity(data));

            try {
                rCode = client.executeMethod(method);

            } catch (Exception e) {
                logger.error("Failed to send data {}", e);

            } finally {
                // Release the connection.
                method.releaseConnection();
            }
            return rCode;
        }
    }

    /**
     * This function does the decoding for a new message from the device
     */
    public String decodeMessage(KM200Device device, byte[] encoded) {
        String retString = null;
        byte[] decodedB64 = null;

        try {
            decodedB64 = Base64.decodeBase64(encoded);
        } catch (Exception e) {
            logger.error("Message is not in valid Base64 scheme: {}", e);
            e.printStackTrace();
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
            logger.error("Exception on encoding: {}", e);
            return null;
        }
    }

    public byte[] encodeMessage(KM200Device device, String data) {
        byte[] encryptedDataB64 = null;

        try {
            // --- create cipher
            byte[] bdata = data.getBytes(device.getCharSet());
            final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(device.getCryptKeyPriv(), "AES"));
            logger.debug("Create padding..");
            int bsize = cipher.getBlockSize();
            logger.debug("Add Padding and Encrypt AES..");
            final byte[] encryptedData = cipher.doFinal(addZeroPadding(bdata, bsize, device.getCharSet()));
            logger.debug("Encrypt B64..");
            try {
                encryptedDataB64 = Base64.encodeBase64(encryptedData);
            } catch (Exception e) {
                logger.error("Base64encoding not possible: {}", e.getMessage());
            }
            return encryptedDataB64;
        } catch (UnsupportedEncodingException | GeneralSecurityException e) {
            // failure to authenticate
            logger.error("Exception on encoding: {}", e);
            return null;
        }
    }
}