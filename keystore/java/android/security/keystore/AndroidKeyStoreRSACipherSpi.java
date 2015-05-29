/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.io.ByteArrayOutputStream;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.MGF1ParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Base class for {@link CipherSpi} providing Android KeyStore backed RSA encryption/decryption.
 *
 * @hide
 */
abstract class AndroidKeyStoreRSACipherSpi extends AndroidKeyStoreCipherSpiBase {

    /**
     * Raw RSA cipher without any padding.
     */
    public static final class NoPadding extends AndroidKeyStoreRSACipherSpi {
        public NoPadding() {
            super(KeymasterDefs.KM_PAD_NONE);
        }

        @Override
        protected void initAlgorithmSpecificParameters() throws InvalidKeyException {}

        @Override
        protected void initAlgorithmSpecificParameters(@Nullable AlgorithmParameterSpec params)
                throws InvalidAlgorithmParameterException {
            if (params != null) {
                throw new InvalidAlgorithmParameterException(
                        "Unexpected parameters: " + params + ". No parameters supported");
            }
        }

        @Override
        protected void initAlgorithmSpecificParameters(@Nullable AlgorithmParameters params)
                throws InvalidAlgorithmParameterException {

            if (params != null) {
                throw new InvalidAlgorithmParameterException(
                        "Unexpected parameters: " + params + ". No parameters supported");
            }
        }

        @Override
        protected AlgorithmParameters engineGetParameters() {
            return null;
        }

        @Override
        protected final int getAdditionalEntropyAmountForBegin() {
            return 0;
        }

        @Override
        @NonNull
        protected KeyStoreCryptoOperationStreamer createMainDataStreamer(
                KeyStore keyStore, IBinder operationToken) {
            if (isEncrypting()) {
                // KeyStore's RSA encryption without padding expects the input to be of the same
                // length as the modulus. We thus have to buffer all input to pad it with leading
                // zeros.
                return new ZeroPaddingEncryptionStreamer(
                        super.createMainDataStreamer(keyStore, operationToken),
                        getModulusSizeBytes());
            } else {
                return super.createMainDataStreamer(keyStore, operationToken);
            }
        }

        /**
         * Streamer which buffers all plaintext input, then pads it with leading zeros to match
         * modulus size, and then sends it into KeyStore to obtain ciphertext.
         */
        private static class ZeroPaddingEncryptionStreamer
                implements KeyStoreCryptoOperationStreamer {

            private final KeyStoreCryptoOperationStreamer mDelegate;
            private final int mModulusSizeBytes;
            private final ByteArrayOutputStream mInputBuffer = new ByteArrayOutputStream();

            private ZeroPaddingEncryptionStreamer(
                    KeyStoreCryptoOperationStreamer delegate,
                    int modulusSizeBytes) {
                mDelegate = delegate;
                mModulusSizeBytes = modulusSizeBytes;
            }

            @Override
            public byte[] update(byte[] input, int inputOffset, int inputLength)
                    throws KeyStoreException {
                if (inputLength > 0) {
                    mInputBuffer.write(input, inputOffset, inputLength);
                }
                return EmptyArray.BYTE;
            }

            @Override
            public byte[] doFinal(byte[] input, int inputOffset, int inputLength)
                    throws KeyStoreException {
                if (inputLength > 0) {
                    mInputBuffer.write(input, inputOffset, inputLength);
                }
                byte[] bufferedInput = mInputBuffer.toByteArray();
                mInputBuffer.reset();
                byte[] paddedInput;
                if (bufferedInput.length < mModulusSizeBytes) {
                    // Pad input with leading zeros
                    paddedInput = new byte[mModulusSizeBytes];
                    System.arraycopy(
                            bufferedInput, 0,
                            paddedInput,
                            paddedInput.length - bufferedInput.length,
                            bufferedInput.length);
                } else {
                    // No need to pad input
                    paddedInput = bufferedInput;
                }
                return mDelegate.doFinal(paddedInput, 0, paddedInput.length);
            }
        }
    }

    /**
     * RSA cipher with PKCS#1 v1.5 encryption padding.
     */
    public static final class PKCS1Padding extends AndroidKeyStoreRSACipherSpi {
        public PKCS1Padding() {
            super(KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT);
        }

        @Override
        protected void initAlgorithmSpecificParameters() throws InvalidKeyException {}

        @Override
        protected void initAlgorithmSpecificParameters(@Nullable AlgorithmParameterSpec params)
                throws InvalidAlgorithmParameterException {
            if (params != null) {
                throw new InvalidAlgorithmParameterException(
                        "Unexpected parameters: " + params + ". No parameters supported");
            }
        }

        @Override
        protected void initAlgorithmSpecificParameters(@Nullable AlgorithmParameters params)
                throws InvalidAlgorithmParameterException {

            if (params != null) {
                throw new InvalidAlgorithmParameterException(
                        "Unexpected parameters: " + params + ". No parameters supported");
            }
        }

        @Override
        protected AlgorithmParameters engineGetParameters() {
            return null;
        }

        @Override
        protected final int getAdditionalEntropyAmountForBegin() {
            return (isEncrypting()) ? getModulusSizeBytes() : 0;
        }
    }

    /**
     * RSA cipher with OAEP encryption padding. Only SHA-1 based MGF1 is supported as MGF.
     */
    abstract static class OAEPWithMGF1Padding extends AndroidKeyStoreRSACipherSpi {

        private static final String MGF_ALGORITGM_MGF1 = "MGF1";

        private int mKeymasterDigest = -1;
        private int mDigestOutputSizeBytes;

        OAEPWithMGF1Padding(int keymasterDigest) {
            super(KeymasterDefs.KM_PAD_RSA_OAEP);
            mKeymasterDigest = keymasterDigest;
            mDigestOutputSizeBytes =
                    (KeymasterUtils.getDigestOutputSizeBits(keymasterDigest) + 7) / 8;
        }

        @Override
        protected final void initAlgorithmSpecificParameters() throws InvalidKeyException {}

        @Override
        protected final void initAlgorithmSpecificParameters(
                @Nullable AlgorithmParameterSpec params) throws InvalidAlgorithmParameterException {
            if (params == null) {
                return;
            }

            if (!(params instanceof OAEPParameterSpec)) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported parameter spec: " + params
                        + ". Only OAEPParameterSpec supported");
            }
            OAEPParameterSpec spec = (OAEPParameterSpec) params;
            if (!MGF_ALGORITGM_MGF1.equalsIgnoreCase(spec.getMGFAlgorithm())) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported MGF: " + spec.getMGFAlgorithm()
                        + ". Only " + MGF_ALGORITGM_MGF1 + " supported");
            }
            String jcaDigest = spec.getDigestAlgorithm();
            int keymasterDigest;
            try {
                keymasterDigest = KeyProperties.Digest.toKeymaster(jcaDigest);
            } catch (IllegalArgumentException e) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported digest: " + jcaDigest, e);
            }
            switch (keymasterDigest) {
                case KeymasterDefs.KM_DIGEST_SHA1:
                case KeymasterDefs.KM_DIGEST_SHA_2_224:
                case KeymasterDefs.KM_DIGEST_SHA_2_256:
                case KeymasterDefs.KM_DIGEST_SHA_2_384:
                case KeymasterDefs.KM_DIGEST_SHA_2_512:
                    // Permitted.
                    break;
                default:
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported digest: " + jcaDigest);
            }
            AlgorithmParameterSpec mgfParams = spec.getMGFParameters();
            if (mgfParams == null) {
                throw new InvalidAlgorithmParameterException("MGF parameters must be provided");
            }
            // Check whether MGF parameters match the OAEPParameterSpec
            if (!(mgfParams instanceof MGF1ParameterSpec)) {
                throw new InvalidAlgorithmParameterException("Unsupported MGF parameters"
                        + ": " + mgfParams + ". Only MGF1ParameterSpec supported");
            }
            MGF1ParameterSpec mgfSpec = (MGF1ParameterSpec) mgfParams;
            String mgf1JcaDigest = mgfSpec.getDigestAlgorithm();
            if (!KeyProperties.DIGEST_SHA1.equalsIgnoreCase(mgf1JcaDigest)) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported MGF1 digest: " + mgf1JcaDigest
                        + ". Only " + KeyProperties.DIGEST_SHA1 + " supported");
            }
            PSource pSource = spec.getPSource();
            if (!(pSource instanceof PSource.PSpecified)) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported source of encoding input P: " + pSource
                        + ". Only pSpecifiedEmpty (PSource.PSpecified.DEFAULT) supported");
            }
            PSource.PSpecified pSourceSpecified = (PSource.PSpecified) pSource;
            byte[] pSourceValue = pSourceSpecified.getValue();
            if ((pSourceValue != null) && (pSourceValue.length > 0)) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported source of encoding input P: " + pSource
                        + ". Only pSpecifiedEmpty (PSource.PSpecified.DEFAULT) supported");
            }
            mKeymasterDigest = keymasterDigest;
            mDigestOutputSizeBytes =
                    (KeymasterUtils.getDigestOutputSizeBits(keymasterDigest) + 7) / 8;
        }

        @Override
        protected final void initAlgorithmSpecificParameters(@Nullable AlgorithmParameters params)
                throws InvalidAlgorithmParameterException {
            if (params == null) {
                return;
            }

            OAEPParameterSpec spec;
            try {
                spec = params.getParameterSpec(OAEPParameterSpec.class);
            } catch (InvalidParameterSpecException e) {
                throw new InvalidAlgorithmParameterException("OAEP parameters required"
                        + ", but not found in parameters: " + params, e);
            }
            if (spec == null) {
                throw new InvalidAlgorithmParameterException("OAEP parameters required"
                        + ", but not provided in parameters: " + params);
            }
            initAlgorithmSpecificParameters(spec);
        }

        @Override
        protected final AlgorithmParameters engineGetParameters() {
            OAEPParameterSpec spec =
                    new OAEPParameterSpec(
                            KeyProperties.Digest.fromKeymaster(mKeymasterDigest),
                            MGF_ALGORITGM_MGF1,
                            MGF1ParameterSpec.SHA1,
                            PSource.PSpecified.DEFAULT);
            try {
                AlgorithmParameters params = AlgorithmParameters.getInstance("OAEP");
                params.init(spec);
                return params;
            } catch (NoSuchAlgorithmException e) {
                throw new ProviderException(
                        "Failed to obtain OAEP AlgorithmParameters", e);
            } catch (InvalidParameterSpecException e) {
                throw new ProviderException(
                        "Failed to initialize OAEP AlgorithmParameters with an IV",
                        e);
            }
        }

        @Override
        protected final void addAlgorithmSpecificParametersToBegin(
                KeymasterArguments keymasterArgs) {
            super.addAlgorithmSpecificParametersToBegin(keymasterArgs);
            keymasterArgs.addInt(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest);
        }

        @Override
        protected final void loadAlgorithmSpecificParametersFromBeginResult(
                @NonNull KeymasterArguments keymasterArgs) {
            super.loadAlgorithmSpecificParametersFromBeginResult(keymasterArgs);
        }

        @Override
        protected final int getAdditionalEntropyAmountForBegin() {
            return (isEncrypting()) ? mDigestOutputSizeBytes : 0;
        }
    }

    public static class OAEPWithSHA1AndMGF1Padding extends OAEPWithMGF1Padding {
        public OAEPWithSHA1AndMGF1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
    }

    public static class OAEPWithSHA224AndMGF1Padding extends OAEPWithMGF1Padding {
        public OAEPWithSHA224AndMGF1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
    }

    public static class OAEPWithSHA256AndMGF1Padding extends OAEPWithMGF1Padding {
        public OAEPWithSHA256AndMGF1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
    }

    public static class OAEPWithSHA384AndMGF1Padding extends OAEPWithMGF1Padding {
        public OAEPWithSHA384AndMGF1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
    }

    public static class OAEPWithSHA512AndMGF1Padding extends OAEPWithMGF1Padding {
        public OAEPWithSHA512AndMGF1Padding() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
    }

    private final int mKeymasterPadding;

    private int mModulusSizeBytes = -1;

    AndroidKeyStoreRSACipherSpi(int keymasterPadding) {
        mKeymasterPadding = keymasterPadding;
    }

    @Override
    protected final void initKey(int opmode, Key key) throws InvalidKeyException {
        if (key == null) {
            throw new InvalidKeyException("Unsupported key: null");
        }
        if (!KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(key.getAlgorithm())) {
            throw new InvalidKeyException("Unsupported key algorithm: " + key.getAlgorithm()
                    + ". Only " + KeyProperties.KEY_ALGORITHM_RSA + " supported");
        }
        AndroidKeyStoreKey keystoreKey;
        if (key instanceof AndroidKeyStorePrivateKey) {
            keystoreKey = (AndroidKeyStoreKey) key;
        } else if (key instanceof AndroidKeyStorePublicKey) {
            keystoreKey = (AndroidKeyStoreKey) key;
        } else {
            throw new InvalidKeyException("Unsupported key type: " + key);
        }

        if (keystoreKey instanceof PrivateKey) {
            if ((opmode != Cipher.DECRYPT_MODE) && (opmode != Cipher.UNWRAP_MODE)) {
                throw new InvalidKeyException("Private key cannot be used with opmode: " + opmode
                        + ". Only DECRYPT_MODE and UNWRAP_MODE supported");
            }
        } else {
            if ((opmode != Cipher.ENCRYPT_MODE) && (opmode != Cipher.WRAP_MODE)) {
                throw new InvalidKeyException("Public key cannot be used with opmode: " + opmode
                        + ". Only ENCRYPT_MODE and WRAP_MODE supported");
            }
        }

        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode = getKeyStore().getKeyCharacteristics(
                keystoreKey.getAlias(), null, null, keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw getKeyStore().getInvalidKeyException(keystoreKey.getAlias(), errorCode);
        }
        int keySizeBits = keyCharacteristics.getInt(KeymasterDefs.KM_TAG_KEY_SIZE, -1);
        if (keySizeBits == -1) {
            throw new InvalidKeyException("Size of key not known");
        }
        mModulusSizeBytes = (keySizeBits + 7) / 8;

        setKey(keystoreKey);
    }

    @Override
    protected final void resetAll() {
        mModulusSizeBytes = -1;
        super.resetAll();
    }

    @Override
    protected final void resetWhilePreservingInitState() {
        super.resetWhilePreservingInitState();
    }

    @Override
    protected void addAlgorithmSpecificParametersToBegin(
            @NonNull KeymasterArguments keymasterArgs) {
        keymasterArgs.addInt(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_RSA);
        keymasterArgs.addInt(KeymasterDefs.KM_TAG_PADDING, mKeymasterPadding);
    }

    @Override
    protected void loadAlgorithmSpecificParametersFromBeginResult(
            @NonNull KeymasterArguments keymasterArgs) {
    }

    @Override
    protected final int engineGetBlockSize() {
        // Not a block cipher, according to the RI
        return 0;
    }

    @Override
    protected final byte[] engineGetIV() {
        // IV never used
        return null;
    }

    @Override
    protected final int engineGetOutputSize(int inputLen) {
        return getModulusSizeBytes();
    }

    protected final int getModulusSizeBytes() {
        if (mModulusSizeBytes == -1) {
            throw new IllegalStateException("Not initialized");
        }
        return mModulusSizeBytes;
    }
}
