package ee.ria.eudi.qeaa.wallet.factory;

import ee.ria.eudi.qeaa.wallet.error.WalletException;
import ee.ria.eudi.qeaa.wallet.model.Credential;
import ee.ria.eudi.qeaa.wallet.model.PresentationClaim;
import ee.ria.eudi.qeaa.wallet.model.PresentationConsent;
import ee.ria.eudi.qeaa.wallet.util.MDocUtil;
import id.walt.mdoc.SimpleCOSECryptoProvider;
import id.walt.mdoc.doc.MDoc;
import id.walt.mdoc.docrequest.MDocRequest;
import id.walt.mdoc.docrequest.MDocRequestBuilder;
import id.walt.mdoc.mdocauth.DeviceAuthentication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ee.ria.eudi.qeaa.wallet.model.Credential.CREDENTIAL_FORMAT_MSO_MDOC;
import static ee.ria.eudi.qeaa.wallet.util.MDocUtil.KEY_ID_DEVICE;

@Slf4j
@Component
@RequiredArgsConstructor
public class VpTokenFactory {
    private static final Pattern PATH_PATTERN = Pattern.compile("^\\$\\['([^']+)'\\]\\['([^']+)'\\]");
    private final SimpleCOSECryptoProvider deviceCryptoProvider;

    public String create(Credential credential, PresentationConsent presentationConsent, String clientId, String nonce) {
        return switch (credential.getFormat()) {
            case CREDENTIAL_FORMAT_MSO_MDOC -> createMsoMDoc(credential, presentationConsent, clientId, nonce);
            case null, default ->
                throw new WalletException("Unable to present credential. Unsupported credential format: " + credential.getFormat());
        };
    }

    private String createMsoMDoc(Credential credential, PresentationConsent presentationConsent, String clientId, String nonce) {
        MDoc mDoc = MDoc.Companion.fromCBORHex(credential.getValue());
        DeviceAuthentication deviceAuthentication = MDocUtil.getDeviceAuthentication(clientId, mDoc.getDocType().getValue(), nonce);
        log.debug("Device authentication for client {} and nonce {} -> cbor hex: {}", clientId, nonce, deviceAuthentication.toDE().toCBORHex());
        MDocRequest mDocRequest = getMDocRequest(mDoc, presentationConsent);
        MDoc mDocPresentation = mDoc.presentWithDeviceSignature(mDocRequest, deviceAuthentication, deviceCryptoProvider, KEY_ID_DEVICE);
        return mDocPresentation.toCBORHex();
    }

    private MDocRequest getMDocRequest(MDoc mDoc, PresentationConsent presentationConsent) {
        String doctype = mDoc.getDocType().getValue();
        MDocRequestBuilder mDocRequestBuilder = new MDocRequestBuilder(doctype);
        List<PresentationClaim> consentedClaims = presentationConsent.getClaims().stream().filter(PresentationClaim::isSelected).toList();
        consentedClaims.forEach(claim -> {
            Pair<String, String> element = getNameSpaceAndElementIdentifier(claim.getPath());
            mDocRequestBuilder.addDataElementRequest(element.getKey(), element.getValue(), claim.isSelected());
        });
        return mDocRequestBuilder.build(null);
    }

    private Pair<String, String> getNameSpaceAndElementIdentifier(String path) {
        Matcher matcher = PATH_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Pair.of(matcher.group(1), matcher.group(2));
        } else {
            throw new WalletException("Invalid presentation request. Invalid path: " + path);
        }
    }
}
