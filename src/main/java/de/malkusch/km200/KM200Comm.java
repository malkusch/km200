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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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

    /**
     * This function checks the capabilities of a service on the device
     */
    @SuppressWarnings("removal")
    public void initObjects(KM200Device device, String service) {
        String id = null, type = null, decodedData = null;
        Integer writeable = 0;
        Integer recordable = 0;
        JSONObject nodeRoot = null;
        KM200CommObject newObject = null;
        logger.debug("Init: {}", service);
        if (device.blacklistMap.contains(service)) {
            logger.debug("Service on blacklist: {}", service);
            return;
        }
        byte[] recData = getDataFromService(device, service.toString());
        try {
            if (recData == null) {
                throw new RuntimeException("Communication is not possible!");
            }
            if (recData.length == 0) {
                throw new RuntimeException("No reply from KM200!");
            }
            /* Look whether the communication was forbidden */
            if (recData.length == 1) {
                newObject = new KM200CommObject(service, "", 0, 0, 0);
                device.serviceMap.put(service, newObject);
                return;
            }
            decodedData = decodeMessage(device, recData);
            if (decodedData == null) {
                throw new RuntimeException("Decoding of the KM200 message is not possible!");
            }
            if (decodedData.length() > 0) {
                nodeRoot = new JSONObject(decodedData);
                type = nodeRoot.getString("type");
                id = nodeRoot.getString("id");
            } else {
                logger.error("Get empty reply");
                return;
            }

            /* Check the service features and set the flags */
            if (nodeRoot.has("writeable")) {
                Integer val = nodeRoot.getInt("writeable");
                logger.debug(val.toString());
                writeable = val;
            }
            if (nodeRoot.has("recordable")) {
                Integer val = nodeRoot.getInt("recordable");
                logger.debug(val.toString());
                recordable = val;
            }
            logger.debug("Typ: {}", type);

            newObject = new KM200CommObject(id, type, writeable, recordable);

            /* Check whether the type is a single value containing a string value */
            if (type.equals("stringValue")) {
                Object valObject = null;
                logger.debug("initDevice: type string value: {}", decodedData.toString());
                valObject = new String(nodeRoot.getString("value"));
                newObject.setValue(valObject);
                if (nodeRoot.has("allowedValues")) {
                    List<String> valParas = new ArrayList<String>();
                    JSONArray paras = nodeRoot.getJSONArray("allowedValues");
                    for (int i = 0; i < paras.length(); i++) {
                        String subJSON = (String) paras.get(i);
                        valParas.add(subJSON);
                    }
                    newObject.setValueParameter(valParas);
                }
                device.serviceMap.put(id, newObject);

            } else if (type
                    .equals("floatValue")) { /* Check whether the type is a single value containing a float value */
                Object valObject = null;
                logger.debug("initDevice: type float value: {}", decodedData.toString());
                valObject = new Float(nodeRoot.getDouble("value"));
                newObject.setValue(valObject);
                if (nodeRoot.has("minValue") && nodeRoot.has("maxValue")) {
                    List<Float> valParas = new ArrayList<Float>();
                    valParas.add(new Float(nodeRoot.getDouble("minValue")));
                    valParas.add(new Float(nodeRoot.getDouble("maxValue")));
                    newObject.setValueParameter(valParas);
                }
                device.serviceMap.put(id, newObject);

            } else if (type.equals("switchProgram")) { /* Check whether the type is a switchProgram */
                logger.debug("initDevice: type switchProgram {}", decodedData.toString());
                newObject.setValue(decodedData.toString());
                device.serviceMap.put(id, newObject);
                /* have to be completed */

            } else if (type.equals("errorList")) { /* Check whether the type is a errorList */
                logger.debug("initDevice: type errorList: {}", decodedData.toString());
                JSONArray errorValues = nodeRoot.getJSONArray("values");
                newObject.setValue(errorValues);
                /* have to be completed */

            } else if (type.equals("refEnum")) { /* Check whether the type is a refEnum */
                logger.debug("initDevice: type refEnum: {}", decodedData.toString());
                device.serviceMap.put(id, newObject);
                JSONArray refers = nodeRoot.getJSONArray("references");
                for (int i = 0; i < refers.length(); i++) {
                    JSONObject subJSON = refers.getJSONObject(i);
                    id = subJSON.getString("id");
                    initObjects(device, id);
                }

            } else if (type.equals("moduleList")) { /* Check whether the type is a moduleList */
                logger.debug("initDevice: type moduleList: {}", decodedData.toString());
                device.serviceMap.put(id, newObject);
                JSONArray vals = nodeRoot.getJSONArray("values");
                for (int i = 0; i < vals.length(); i++) {
                    JSONObject subJSON = vals.getJSONObject(i);
                    id = subJSON.getString("id");
                    initObjects(device, id);
                }

            } else if (type.equals("yRecording")) { /* Check whether the type is a yRecording */
                logger.debug("initDevice: type yRecording: {}", decodedData.toString());
                device.serviceMap.put(id, newObject);
                /* have to be completed */

            } else if (type.equals("systeminfo")) { /* Check whether the type is a systeminfo */
                logger.debug("initDevice: type systeminfo: {}", decodedData.toString());
                JSONArray sInfo = nodeRoot.getJSONArray("values");
                newObject.setValue(sInfo);
                device.serviceMap.put(id, newObject);
                /* have to be completed */

            } else { /* Unknown type */
                logger.info("initDevice: type unknown for service: {}",
                        service.toString() + "Data:" + decodedData.toString());
                newObject.setValue(decodedData);
                device.serviceMap.put(id, newObject);
            }
        } catch (

        JSONException e) {
            logger.error("Parsingexception in JSON: {} data: {}", e, decodedData);
            e.printStackTrace();
        }
    }
}