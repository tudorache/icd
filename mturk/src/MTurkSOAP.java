import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import edu.mit.csail.uid.turkit.util.Base64;
import edu.mit.csail.uid.turkit.util.U;

public class MTurkSOAP implements Serializable {

	public String id, secretKey;
	public boolean sandbox;

	public MTurkSOAP(String id, String secretKey, boolean sandbox) {
		this.id = id;
		this.secretKey = secretKey;
		this.sandbox = sandbox;
	}

	private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

	/*
	 * A date formatter for XML DateTimes.
	 */
	public static final SimpleDateFormat xmlTimeFormat = new SimpleDateFormat(
			"yyyy-MM-dd'T'HH:mm:ss'Z'");

	static {
		xmlTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	/**
	 * Gets the current time as an XML DateTime string.
	 */
	public static String getTimestamp() {
		return xmlTimeFormat.format(new Date());
	}

	/**
	 * Computes a Signature for use in MTurk REST requests.
	 */
	public static String getSignature(String service, String operation,
			String timestamp, String secretKey) throws Exception {

		Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
		mac.init(new SecretKeySpec(secretKey.getBytes(), HMAC_SHA1_ALGORITHM));
		return Base64.encodeBytes(mac.doFinal((service + operation + timestamp)
				.getBytes()));
	}

	/**
	 * Performs a SOAP request on MTurk. The <code>paramsList</code> must be a
	 * sequence of strings of the form a1, b1, a2, b2, a3, b3 ... Where aN is a
	 * parameter name, and bN is the value for that parameter. Most common
	 * parameters have suitable default values, namely: Version, Timestamp,
	 * Query, and Signature.
	 */
	public String soapRequest(String operation, String XMLstring)
			throws Exception {

		URL url = new URL(
				sandbox ? "https://mechanicalturk.sandbox.amazonaws.com/"
						: "https://mechanicalturk.amazonaws.com/");

		String timestamp = getTimestamp();
		String sig = getSignature("AWSMechanicalTurkRequester", operation,
				timestamp, secretKey);

		StringBuffer x = new StringBuffer();
		x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n\t\t<soapenv:Envelope\n\t\t     xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"\n\t\t     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n\t\t     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n\t\t  <soapenv:Body>\n\t\t    <");
		x.append(operation);
		x.append(" xmlns=\"http://mechanicalturk.amazonaws.com/AWSMechanicalTurkRequester/2008-08-02\">");
		x.append("<AWSAccessKeyId>");
		x.append(id);
		x.append("</AWSAccessKeyId>");
		x.append("<Timestamp>");
		x.append(timestamp);
		x.append("</Timestamp>");
		x.append("<Signature>");
		x.append(sig);
		x.append("</Signature>");
		x.append("<Request>");

		x.append(XMLstring);

		x.append("</Request>");
		x.append("</");
		x.append(operation);
		x.append(">");
		x.append("</soapenv:Body></soapenv:Envelope>");

		String soap = x.toString();

		for (int t = 0; t < 100; t++) {
			try {
				HttpURLConnection c = (HttpURLConnection) url.openConnection();
				c.addRequestProperty("Content-Type",
						"application/soap+xml; charset=utf-8");
				String s = U.webPost(c, soap);
				Matcher m = Pattern.compile(
						"(?msi)<soapenv:Body>(.*)</soapenv:Body>").matcher(s);
				if (m.find()) {
					s = m.group(1);
					s = s.replaceFirst(" xmlns=\"[^\"]*\"", "");
					return s;
				} else {
					throw new IllegalArgumentException(
							"unexpected response from MTurk: " + s);
				}
			} catch (IOException e) {
				if (e.getMessage().startsWith(
						"Server returned HTTP response code: 503")) {
					Thread.sleep(100 + (int) Math.min(3000, Math.pow(t, 3)));
				} else {
					throw e;
				}
			}
		}
		throw new Exception("MTurk seems to be down.");

	}

	public static String postQuestion(String title, String description) {
		String xml = "";
		xml = xml + "<Title>" + title + "</Title>";
		xml = xml + "<Description>" + description + "</Description>";
		xml = xml
				+ "<AssignmentDurationInSeconds>3000</AssignmentDurationInSeconds>";
		xml = xml
				+ "<Reward><Amount>0.01</Amount><CurrencyCode>USD</CurrencyCode></Reward>";
		// xml = xml
		// // +
		// "<QuestionForm xmlns='http://mechanicalturk.amazonaws.com/AWSMechanicalTurkDataSchemas/2005-10-01/QuestionForm.xsd'>"
		// + "<Question>"
		// + "<QuestionIdentifier>likelytowin</QuestionIdentifier>"
		// + "<DisplayName>The Next Move</DisplayName>"
		// + "<IsRequired>true</IsRequired>" + "<QuestionContent>"
		// + "<Text>"
		// + "How likely is it that player X will win this game?"
		// + "</Text>" + "</QuestionContent>" + "<AnswerSpecification>"
		// + "<SelectionAnswer>"
		// + "<StyleSuggestion>radiobutton</StyleSuggestion>"
		// + "<Selections>" + "<Selection>"
		// + "<SelectionIdentifier>notlikely</SelectionIdentifier>"
		// + "<Text>Not likely</Text>" + "</Selection>" + "<Selection>"
		// + "<SelectionIdentifier>unsure</SelectionIdentifier>"
		// + "<Text>It could go either way</Text>" + "</Selection>"
		// + "<Selection>"
		// + "<SelectionIdentifier>likely</SelectionIdentifier>"
		// + "<Text>Likely</Text>" + "</Selection>" + "</Selections>"
		// + "</SelectionAnswer>" + "</AnswerSpecification>"
		// + "</Question>";
		// // + "</QuestionForm>";
		xml = xml
				+ "<Question>"
				+ "<QuestionIdentifier>nextmove</QuestionIdentifier>"
				+ "<DisplayName>The Next Move</DisplayName>"
				+ "<IsRequired>true</IsRequired>"
				+ "<QuestionContent>"
				+ "<Text>"
				+ "What are the coordinates of the best move for player 'X' in this game?"
				+ "</Text>" + "</QuestionContent>" + "<AnswerSpecification>"
				+ "<FreeTextAnswer>" + "<Constraints>"
				+ "<Length minLength='2' maxLength='2' />" + "</Constraints>"
				+ "<DefaultText>C1</DefaultText>" + "</FreeTextAnswer>"
				+ "</AnswerSpecification>" + "</Question>";
		return xml;
	}

	public static void main(String[] args) throws Exception {
		String xml = "<HITId>2CIFK0COK2JEYC7CRB0L1W6O00DKRQ</HITId><ResponseGroup>Minimal</ResponseGroup><ResponseGroup>HITDetail</ResponseGroup>";
		String xml2 = "<HITId>2CIFK0COK2JEYC7CRB0L1W6O00DKRQ</HITId>";
		MTurkSOAP tmp = new MTurkSOAP("AKIAIZCFLKY2J2WPBKZA",
				"oVjZAE43v7FHCYrnydGhcgu5U2pDG/XS0Q/shjcy", true);
		String tmps = tmp.soapRequest("GetAssignmentsForHIT", xml2);
		// String tmps = tmp.soapRequest("GetReviewableHITs", "");
		// String xml3 = postQuestion("test title", "test description");
		// String tmps = tmp.soapRequest("CreateHIT", xml3);
		System.out.println(tmps);
	}

}
