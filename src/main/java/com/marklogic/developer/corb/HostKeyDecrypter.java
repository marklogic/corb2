/*
 * Copyright (c) 2004-2016 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.developer.corb;

import static java.util.logging.Level.INFO;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Scanner;
import java.util.logging.Logger;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

/**
 * Class that uses a private key associate with a particular host Key is
 * calculated based on SHA-256 hash of internal server ids. Also has and
 * endpoint to encrypt plaintext passwords
 *
 * @author Richard Kennedy
 */
public class HostKeyDecrypter extends AbstractDecrypter {

    private static byte[] privateKey;
    private static final byte[] DEFAULT_BYTES = {45, 32, 67, 34, 67, 23, 21, 45, 7, 89, 3, 27, 39, 62, 15};
    private static final byte[] HARD_CODED_BYTES = {120, 26, 58, 29, 43, 77, 95, 103, 29, 86, 97, 105, 52, 16, 42, 63, 37, 100, 45, 109, 108, 79, 75, 71, 11, 46, 36, 62, 124, 12, 7, 127};
    private static final String AES = "AES";
    private static final String USAGE_FORMAT = "java -cp marklogic-corb-" + AbstractManager.VERSION + ".jar " + HostKeyDecrypter.class.getName() + "{0} ";
    private static final String EXCEPTION_MGS_SERIAL_NOT_FOUND = "Unable to find serial number on {0}"; 
    private static final String METHOD_TEST = "test";
    private static final String METHOD_ENCRYPT = "encrypt";
    // currently only usage is encrypt
    private static final String USAGE = "Encrypt:\n "
            + MessageFormat.format(USAGE_FORMAT, new Object[]{METHOD_ENCRYPT + " clearText"})
            + "\nTest:\n "
            + MessageFormat.format(USAGE_FORMAT, new Object[]{METHOD_TEST});

    protected static final Logger LOG = Logger.getLogger(HostKeyDecrypter.class.getName());
        
    protected enum OSType {

        Windows {
            /**
             * get bios serial number on windows machine
             *
             * @return byte[] bios serial number
             */
            @Override
            public byte[] getSN() {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = null;
                boolean isSN = false;
                Scanner sc = null;
                try {
                    br = read("wmic bios get serialnumber");
                    sc = new Scanner(br);
                    while (sc.hasNext()) {
                        String next = sc.next();
                        if ("SerialNumber".equals(next) || isSN) {
                            isSN = true;
                            next = sc.next();
                            sb.append(next);
                        }
                    }
                    String sn = sb.toString();
                    if (!sn.isEmpty()) {
                        return sn.getBytes();
                    } else {
                        throw new IllegalStateException(MessageFormat.format(EXCEPTION_MGS_SERIAL_NOT_FOUND, new Object[]{this.toString()}));
                    }
                } finally {
                    if (sc != null) {
                        sc.close();
                    } //Scanner doesn't implement Closable in 1.6
                    closeOrThrowRuntime(br);
                }
            }
        },
        Mac {
            /**
             * get bios serial number on mac machine
             *
             * @return byte[] bios serial number
             */
            @Override
            public byte[] getSN() {
                String line = null;
                String marker = "Serial Number";
                BufferedReader br = null;

                try {
                    br = read("/usr/sbin/system_profiler SPHardwareDataType");
                    while ((line = br.readLine()) != null) {
                        if (line.contains(marker)) {
                            String sn = line.split(marker)[1].trim();
                            return sn.getBytes();
                        }
                    }
                    throw new IllegalStateException(MessageFormat.format(EXCEPTION_MGS_SERIAL_NOT_FOUND, new Object[]{this.toString()}));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    closeOrThrowRuntime(br);
                }
            }
        },
        Linux {
            /**
             * get bios serial number on linux machine uses lshal command that
             * is part of the hal package
             *
             * @return byte[] bios serial number
             */
            @Override
            public byte[] getSN() {
                String line = null;
                String marker = "system.hardware.serial";
                BufferedReader br = null;

                try {
                    br = read("lshal");
                    while ((line = br.readLine()) != null) {
                        if (line.contains(marker)) {
                            String sn = line.split(marker)[1].trim();
                            return sn.getBytes();
                        }
                    }
                    throw new IllegalStateException(MessageFormat.format(EXCEPTION_MGS_SERIAL_NOT_FOUND, new Object[]{this.toString()}));
                } catch (IOException e) {
                    throw new RuntimeException("Required to have lshal command installed on linux machine", e);
                } finally {
                    closeOrThrowRuntime(br);
                }
            }
        },
        Other {
            @Override
            public byte[] getSN() {
                return DEFAULT_BYTES;
            }
        };
        
        public abstract byte[] getSN();
        
        private static void closeOrThrowRuntime(Closeable obj) {
            if (obj != null) {
                try {
                    obj.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private static BufferedReader read(String command) {
            Runtime runtime = Runtime.getRuntime();
            Process process = null;
            try {
                process = runtime.exec(command.split(" "));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            OutputStream os = process.getOutputStream();
            try {
                os.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            InputStream is = process.getInputStream();
            return new BufferedReader(new InputStreamReader(is));
        }
    }

    @Override
    protected void init_decrypter() throws IOException, ClassNotFoundException {
        try {
            privateKey = getPrivateKey();
            LOG.log(INFO, "Initialized HostKeyDecrypter");
        } catch (NoSuchAlgorithmException e) {
            new RuntimeException("Error constructing private key", e);
        }
    }

    @Override
    protected String doDecrypt(String property, String value) {
        String decryptedText = null;
        try {
            decryptedText = decrypt(value);
        } catch (Exception e) {
            new RuntimeException("Unabled to decrypt property:" + property, e);
        }
        return decryptedText == null ? value : decryptedText;
    }

    /**
     * Performs an xor on two byte arrays by doing an xor on each byte and
     * putting result into another byte array arrays of different length will
     * create an array of the longest where extra bytes are xor'd against 0 byte
     *
     * @author Richard Kennedy
     * @param byteOne
     * @param byteTwo
     * @return
     */
    public static byte[] xor(byte[] byteOne, byte[] byteTwo) {
        int max;
        if (byteOne.length > byteTwo.length) {
            max = byteOne.length;
        } else {
            max = byteTwo.length;
        }
        byte[] result = new byte[max];
        for (int i = 0; i < max; i++) {
            byte one;
            byte two;
            if (i < byteOne.length) {
                one = byteOne[i];
            } else {
                one = (byte) 0;
            }
            if (i < byteTwo.length) {
                two = byteTwo[i];
            } else {
                two = (byte) 0;
            }
            result[i] = (byte) (one ^ two);
        }
        return result;
    }

    /**
     * Performs a SHA-256 hash
     *
     * @author Richard Kennedy
     * @param input
     * @return
     * @throws java.security.NoSuchAlgorithmException
     */
    public static byte[] getSHA256Hash(byte[] input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(input);
        return md.digest();
    }

    /**
     * Creating a private key by doing an xor on hostname and mac address Then
     * doing a SHA-256 has to generate a 256 key for AES
     *
     * @author Richard Kennedy
     * @throws UnknownHostException
     * @throws SocketException
     * @throws NoSuchAlgorithmException
     */
    private static byte[] getPrivateKey() throws UnknownHostException, SocketException, NoSuchAlgorithmException {
        InetAddress ip = InetAddress.getLocalHost();
        NetworkInterface network = NetworkInterface.getByInetAddress(ip);
        byte[] mac = network.getHardwareAddress();
        byte[] hostname = ip.getHostName().getBytes();
        byte[] biosSN = getSerialNumber();
        // doing an xor on mac address and hostname
        byte[] xorResult = xor(HARD_CODED_BYTES, xor(biosSN, xor(mac, hostname)));
        return getSHA256Hash(xorResult);
    }

    private static byte[] getSerialNumber() {
        String os = System.getProperty("os.name");
        return getOperatingSystemType(os).getSN();
    }

    protected static OSType getOperatingSystemType(String osName) {
        OSType type = OSType.Other;
        if (osName != null) {
            String os = osName.toLowerCase(Locale.ENGLISH);
            if ((os.contains("mac")) || (os.contains("darwin"))) {
                type = OSType.Mac;
            } else if (os.contains("win")) {
                type = OSType.Windows;
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                type = OSType.Linux;
            }
        }
        return type;
    }

    /**
     * Encrypts plaintext password using private key internal to host and AES
     * 256 algorithm
     *
     * @author Richard Kennedy
     * @param plaintext
     * @return
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws BadPaddingException
     * @throws IllegalBlockSizeException
     * @throws SocketException
     * @throws UnknownHostException
     */
    public static String encrypt(String plaintext)
            throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            IllegalBlockSizeException, BadPaddingException, UnknownHostException, SocketException {
        // secret key based off of internal private key
        byte[] encryptionPrivateKey = getPrivateKey();
        Key key = new SecretKeySpec(encryptionPrivateKey, AES);
        Cipher cipher = Cipher.getInstance(AES);
        // encrypting with private key and random for initialization vector
        cipher.init(Cipher.ENCRYPT_MODE, key, new SecureRandom());
        byte[] encryptedVal = cipher.doFinal(plaintext.getBytes());
        // base64 encode before returning
        return DatatypeConverter.printHexBinary(encryptedVal);
    }

    /**
     * decrypts encrypted password using private key internal to host and AES
     * 256 algorithm and returns plaintext password
     *
     * @param String encrypted text
     * @author Richard Kennedy
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     */
    private static String decrypt(String encryptedText) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException {
        byte[] encryptedTextBytes = DatatypeConverter.parseHexBinary(encryptedText);
        SecretKeySpec secretSpec = new SecretKeySpec(privateKey, AES);
        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(Cipher.DECRYPT_MODE, secretSpec, new SecureRandom());
        byte[] decryptedTextBytes = null;
        try {
            decryptedTextBytes = cipher.doFinal(encryptedTextBytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String(decryptedTextBytes);
    }

    /**
     * command line utility for doing password encryption
     *
     * @author Richard Kennedy
     * @param args
     * @throws java.lang.Exception
     */
    public static void main(String[] args) throws Exception {
        String method = (args != null && args.length > 0) ? args[0].trim() : "";

        if (METHOD_ENCRYPT.equals(method) && args.length == 2) {
            System.out.println(encrypt(args[1].trim()));
        } else if (METHOD_TEST.equals(method)) {
            HostKeyDecrypter decrypter = new HostKeyDecrypter();
            decrypter.init(System.getProperties());
            String original = "234Helloworld!!!";
            System.out.println("Password is :" + original);
            String password = encrypt(original);
            System.out.println("Encrypted Password is :" + password);
            System.out.println("Decrypted password:" + decrypter.doDecrypt("Property", password));
        } else {
            System.out.println(USAGE);
        }
    }
}
