/*
 * Copyright (c) 2007-2008, Arshan Dabirsiaghi, Jason Li
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * - Redistributions of source code must retain the above copyright notice, 
 * 	 this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * - Neither the name of OWASP nor the names of its contributors may be used to
 *   endorse or promote products derived from this software without specific
 *   prior written permission.
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
package org.owasp.validator.css;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;

import org.owasp.validator.html.CleanResults;
import org.owasp.validator.html.Policy;
import org.owasp.validator.html.util.HTMLEntityEncoder;
import org.w3c.css.sac.CSSException;
import org.w3c.css.sac.DocumentHandler;
import org.w3c.css.sac.InputSource;
import org.w3c.css.sac.LexicalUnit;
import org.w3c.css.sac.SACMediaList;
import org.w3c.css.sac.Selector;
import org.w3c.css.sac.SelectorList;

/**
 * A implementation of a SAC DocumentHandler for CSS validation. The appropriate
 * validation method is called whenever the handler is invoked by the parser.
 * The handler also builds a clean CSS document as the original CSS is scanned.
 * 
 * NOTE: keeping state in this class is not ideal as handler style parsing a la
 * SAX should generally be event driven. However, there is not a fully
 * implemented "DOM" equivalent to CSS at this time. Java has a StyleSheet class
 * that could accomplish this "DOM" like behavior but it has yet to be fully
 * implemented.
 * 
 * @see javax.swing.text.html.StyleSheet
 * @author Jason Li
 * 
 */
public class CssHandler implements DocumentHandler {

	/**
	 * The style sheet as it is being built by the handler
	 */
	private StringBuffer styleSheet = new StringBuffer();

	/**
	 * The validator to use when CSS constituents are encountered
	 */
	private final CssValidator validator;

	/**
	 * The policy file to use in validation
	 */
	private final Policy policy;

	/**
	 * The encaspulated results including the error messages
	 */
	private final CleanResults results;

	/**
	 * A queue of imported stylesheets; used to track imported stylesheets
	 */
	private final LinkedList importedStyleSheets;

	/**
	 * The tag currently being examined (if any); used for inline stylesheet
	 * error messages
	 */
	private final String tagName;

	/**
	 * Indicates whether we are scanning a stylesheet or an inline declaration.
	 * true if this is an inline declaration; false otherwise
	 */
	private final boolean isInline;

	/**
	 * Indicates whether the handler is currently parsing the contents between
	 * an open selector tag and an close selector tag
	 */
	private boolean selectorOpen = false;

	/**
	 * Constructs a handler for stylesheets using the given policy and queue for
	 * imported stylesheets.
	 * 
	 * @param policy
	 *            the policy to use
	 * @param embeddedStyleSheets
	 *            the queue of stylesheets imported
	 */
	public CssHandler(Policy policy, LinkedList embeddedStyleSheets) {
		this(policy, embeddedStyleSheets, null);
	}

	/**
	 * Constructs a handler for inline style declarations using the given policy
	 * and queue for imported stylesheets.
	 * 
	 * @param policy
	 *            the policy to use
	 * @param embeddedStyleSheets
	 *            the queue of stylesheets imported
	 * @param tagName
	 *            the associated tag name with this inline style
	 */
	public CssHandler(Policy policy, LinkedList embeddedStyleSheets, String tagName) {
		this.policy = policy;
		this.results = new CleanResults(null);
		this.validator = new CssValidator(policy);
		this.importedStyleSheets = embeddedStyleSheets;
		this.tagName = tagName;
		this.isInline = (tagName != null);
	}

	/**
	 * Returns the encapsulated results generated by the handler during parsing.
	 * 
	 * @return the <code>CleanResults</code> object containing the results
	 *         generated by the handler
	 */
	public CleanResults getResults() {
		// Always ensure results contain most recent generation of stylesheet
		results.setCleanHTML(styleSheet.toString());
		return results;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#comment(java.lang.String)
	 */
	public void comment(String text) throws CSSException {
		// validate comment
		if (policy.getRegularExpression("cssCommentText").getPattern().matcher(
				text).matches()) {
			styleSheet.append("/* " + text + " */\n");
		} else {
			results
					.addErrorMessage("The comment field was filtered out for security reasons. The value of the comment field was <u>"
							+ HTMLEntityEncoder.htmlEntityEncode(text) + "</u>");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#ignorableAtRule(java.lang.String)
	 */
	public void ignorableAtRule(String atRule) throws CSSException {
		// this method is called when the parser hits an unrecognized
		// @-rule. Like the page/media/font declarations, this is
		// CSS2+ stuff
		results.addErrorMessage("The @-rule <u>"
				+ HTMLEntityEncoder.htmlEntityEncode(atRule)
				+ "</u> could not be processed for security reasons.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#importStyle(java.lang.String,
	 *      org.w3c.css.sac.SACMediaList, java.lang.String)
	 */
	public void importStyle(String uri, SACMediaList media,
			String defaultNamespaceURI) throws CSSException {
		
		if (!Boolean.valueOf(policy.getDirective("embedStyleSheets"))
				.booleanValue()) {
			results
					.addErrorMessage("Importing of stylesheets has not been enabled");
		} else if (!policy.getRegularExpression("offsiteURL").getPattern()
				.matcher(uri).matches()
				&& !policy.getRegularExpression("onsiteURL").getPattern()
						.matcher(uri).matches()) {
			results
					.addErrorMessage("The url for stylesheet import could not be accepted for security reasons. The url was "
							+ HTMLEntityEncoder.htmlEntityEncode(uri));
		} else {
			
			try {
				URI importedStyleSheet = new URI(uri);

				// canonicalize the URI
				importedStyleSheet.normalize();

				if (!importedStyleSheet.isAbsolute()) {
					// we have no concept of relative reference for free form
					// text as an end user can't know where the corresponding
					// free form will end up
					results
							.addErrorMessage("Cascading stylesheet located at <b>" + HTMLEntityEncoder.htmlEntityEncode(uri) + "</b> could not be embedded as relative URIs are not supported");
					return;
				}

				importedStyleSheets.add(new InputSource(
						new InputStreamReader(importedStyleSheet.toURL()
								.openStream())));
			} catch (URISyntaxException use) {
				results.addErrorMessage("Encountered invalid @import uri");
			} catch (IOException ioe) {
				results
						.addErrorMessage("IO error importing stylesheet: "
								+ uri);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#namespaceDeclaration(java.lang.String,
	 *      java.lang.String)
	 */
	public void namespaceDeclaration(String prefix, String uri)
			throws CSSException {
		// CSS3 - Namespace declaration - ignore for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#startDocument(org.w3c.css.sac.InputSource)
	 */
	public void startDocument(InputSource arg0) throws CSSException {
		// no-op
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#endDocument(org.w3c.css.sac.InputSource)
	 */
	public void endDocument(InputSource source) throws CSSException {
		// no-op
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#startFontFace()
	 */
	public void startFontFace() throws CSSException {
		// CSS2 Font Face declaration - ignore this for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#endFontFace()
	 */
	public void endFontFace() throws CSSException {
		// CSS2 Font Face declaration - ignore this for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#startMedia(org.w3c.css.sac.SACMediaList)
	 */
	public void startMedia(SACMediaList media) throws CSSException {
		// CSS2 Media declaration - ignore this for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#endMedia(org.w3c.css.sac.SACMediaList)
	 */
	public void endMedia(SACMediaList media) throws CSSException {
		// CSS2 Media declaration - ignore this for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#startPage(java.lang.String,
	 *      java.lang.String)
	 */
	public void startPage(String name, String pseudoPage) throws CSSException {
		// CSS2 Page declaration - ignore this for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#endPage(java.lang.String,
	 *      java.lang.String)
	 */
	public void endPage(String name, String pseudoPage) throws CSSException {
		// CSS2 Page declaration - ignore this for now
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#startSelector(org.w3c.css.sac.SelectorList)
	 */
	public void startSelector(SelectorList selectors) throws CSSException {

		// keep track of number of valid selectors from this rule
		int selectorCount = 0;

		// check each selector from this rule
		for (int i = 0; i < selectors.getLength(); i++) {
			Selector selector = selectors.item(i);

			if (selector != null) {
				String selectorName = selector.toString();

				// if the selector is valid, add to list
				if (validator.isValidSelector(selectorName, selector, results)) {
					if (selectorCount > 0) {
						styleSheet.append(',');
						styleSheet.append(' ');
					}
					styleSheet.append(selectorName);

					selectorCount++;

				} else {
					if (tagName != null) {
						results
								.addErrorMessage("The <b>"
										+ HTMLEntityEncoder
												.htmlEntityEncode(tagName)
										+ "</b> tag had a <b>style</b> selector that was invalid. The value of <u>"
										+ HTMLEntityEncoder
												.htmlEntityEncode(selector
														.toString())
										+ "</u> could not be processed for security reasons.");
					} else {
						results
								.addErrorMessage("The <b>style</b> tag had a selector that was invalid. The value <u>"
										+ HTMLEntityEncoder
												.htmlEntityEncode(selector
														.toString())
										+ "</u> could not be processed for security reasons.");
					}

				}
			}
		}

		// if and only if there were selectors that were valid, append
		// appropriate open brace and set state to within selector
		if (selectorCount > 0) {
			styleSheet.append(' ');
			styleSheet.append('{');
			styleSheet.append('\n');
			selectorOpen = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#endSelector(org.w3c.css.sac.SelectorList)
	 */
	public void endSelector(SelectorList selectors) throws CSSException {
		// if we are in a state within a selector, close brace
		if (selectorOpen) {
			styleSheet.append('}');
			styleSheet.append('\n');
		}

		// reset state
		selectorOpen = false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.w3c.css.sac.DocumentHandler#property(java.lang.String,
	 *      org.w3c.css.sac.LexicalUnit, boolean)
	 */
	public void property(String name, LexicalUnit value, boolean important)
			throws CSSException {
		// only bother validating and building if we are either inline or within
		// a selector tag
		
		
		if (!selectorOpen && !isInline) {
			return;
		}

		// validate the property
		if (validator.isValidProperty(name, value)) {

			styleSheet.append('\t');
			styleSheet.append(name);
			styleSheet.append(':');

			// append all values
			while (value != null) {
				styleSheet.append(' ');
				styleSheet.append(validator.lexicalValueToString(value));
				value = value.getNextLexicalUnit();
			}
			styleSheet.append(';');
			styleSheet.append('\n');

		} else {
			
			if (tagName != null) {
				results
						.addErrorMessage("The <b>"
								+ HTMLEntityEncoder.htmlEntityEncode(tagName)
								+ "</b> tag had a style property that could not be accepted for security reasons. The <b>"
								+ HTMLEntityEncoder.htmlEntityEncode(name)
								+ "</b> property had a value of <u>"
								+ HTMLEntityEncoder.htmlEntityEncode(validator
										.lexicalValueToString(value)) + "</u>");
			} else {
				results
						.addErrorMessage("The <b>style</b> tag had a property <b>"
								+ HTMLEntityEncoder.htmlEntityEncode(name)
								+ "</b> that could not be accepted for security reasons. The property had a value of <u>"
								+ HTMLEntityEncoder.htmlEntityEncode(validator
										.lexicalValueToString(value)) + "</u>");
			}

		}
	}
}
