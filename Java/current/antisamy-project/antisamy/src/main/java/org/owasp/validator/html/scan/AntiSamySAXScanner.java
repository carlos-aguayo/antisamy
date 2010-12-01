/*
 * Copyright (c) 2007-2010, Arshan Dabirsiaghi, Jason Li
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * Neither the name of OWASP nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.owasp.validator.html.scan;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Date;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.xerces.xni.parser.XMLDocumentFilter;
import org.cyberneko.html.parsers.SAXParser;
import org.owasp.validator.html.CleanResults;
import org.owasp.validator.html.Policy;
import org.owasp.validator.html.ScanException;
import org.owasp.validator.html.util.ErrorMessageUtil;
import org.xml.sax.InputSource;

public class AntiSamySAXScanner extends AbstractAntiSamyScanner {

	public AntiSamySAXScanner(Policy policy) {
		super(policy);
	}

	public CleanResults getResults() {
		return null;
	}

	public CleanResults scan(String html, String inputEncoding, String outputEncoding) throws ScanException {

		if (html == null) {
			throw new ScanException(new NullPointerException("Null input"));
		}

		int maxInputSize = policy.getMaxInputSize();

		if (maxInputSize < html.length()) {
			addError(ErrorMessageUtil.ERROR_INPUT_SIZE, new Object[] { new Integer(html.length()), new Integer(maxInputSize) });
			throw new ScanException(errorMessages.get(0).toString());
		}

		MagicSAXFilter filter = new MagicSAXFilter(policy, messages);
		XMLDocumentFilter[] filters = { filter };

		try {
			SAXParser parser = new SAXParser();
			parser.setFeature("http://xml.org/sax/features/namespaces", false);
			parser.setFeature("http://cyberneko.org/html/features/balance-tags/document-fragment", true);
			parser.setFeature("http://cyberneko.org/html/features/scanner/cdata-sections", true);
			
			parser.setProperty("http://cyberneko.org/html/properties/filters", filters);
			parser.setProperty("http://cyberneko.org/html/properties/names/elems", "lower");

			Date start = new Date();

			SAXSource source = new SAXSource(parser, new InputSource(new StringReader(html)));
			StringWriter out = new StringWriter();
			StreamResult result = new StreamResult(out);

			TransformerFactory transformerFactory = TransformerFactory.newInstance();

			Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "no");
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			transformer.setOutputProperty(OutputKeys.METHOD, "html");

			transformer.transform(source, result);

			Date end = new Date();

			// System.out.println(out.getBuffer().toString());
			errorMessages = filter.getErrorMessages();
			return new CleanResults(start, end, out.getBuffer().toString(), null, errorMessages);

		} catch (Exception e) {
			throw new ScanException(e);
		}

	}

}
