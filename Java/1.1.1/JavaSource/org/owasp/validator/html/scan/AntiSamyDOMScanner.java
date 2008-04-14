/*
 * Copyright (c) 2007-2008, Arshan Dabirsiaghi, Jason Li
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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.regex.Pattern;

import org.apache.xerces.dom.DocumentImpl;
import org.apache.xml.serialize.HTMLSerializer;
import org.apache.xml.serialize.OutputFormat;
import org.apache.xml.serialize.XHTMLSerializer;
import org.cyberneko.html.parsers.DOMFragmentParser;
import org.owasp.validator.css.CssScanner;
import org.owasp.validator.html.CleanResults;
import org.owasp.validator.html.Policy;
import org.owasp.validator.html.PolicyException;
import org.owasp.validator.html.ScanException;
import org.owasp.validator.html.model.Attribute;
import org.owasp.validator.html.model.Tag;
import org.owasp.validator.html.util.HTMLEntityEncoder;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


/**
 * This is where the magic lives. All the scanning/filtration logic resides here, but it should not be called
 * directly. All scanning should be done through a <code>AntiSamy.scan()</code> method.
 * 
 * @author Arshan Dabirsiaghi
 *
 */

public class AntiSamyDOMScanner {
	
	private Policy policy;
	private CleanResults results = null;
	private ArrayList errorMessages = new ArrayList();
	private Document document = new DocumentImpl();
	private DocumentFragment dom = document.createDocumentFragment();
	
	public static final String DEFAULT_ENCODING_ALGORITHM = "UTF-8";
	

	
	/**
	 * This is where the magic lives.
	 * @param html A String whose contents we want to scan.
	 * @return A <code>CleanResults</code> object with an <code>XMLDocumentFragment</code> object and its String representation, as well as some scan statistics.
	 * @throws ScanException
	 */
	public CleanResults scan(String html, String inputEncoding, String outputEncoding) throws ScanException {
		
		if ( html == null ) {
			throw new ScanException("No input (null)");
		}
		
		int maxInputSize = Policy.DEFAULT_MAX_INPUT_SIZE;
		
		try {
			maxInputSize = Integer.parseInt(policy.getDirective("maxInputSize"));
		} catch (NumberFormatException nfe) {}
		
		if ( maxInputSize < html.length() ) {
			throw new ScanException("File size ["+html.length()+"] is larger than maximum [" + maxInputSize + "]");
		}
		
		Date start = new Date();
		
		try {
			
			/*
			 * We have to replace any invalid XML characters to prevent NekoHTML from breaking when it gets passed
			 * encodings like %21.
			 */
			
			html = stripNonValidXMLCharacters(html);
			
			/*
			 * First thing we do is call the HTML cleaner ("NekoHTML") on it with the appropriate options. We choose
			 * not to omit tags due to the fallibility of our own listing in the ever changing world
			 * of W3C.
			 */
			
			DOMFragmentParser parser = new DOMFragmentParser();
			parser.setProperty("http://cyberneko.org/html/properties/names/elems", "lower");
			parser.setProperty("http://cyberneko.org/html/properties/default-encoding",inputEncoding);

			parser.parse(new InputSource(new StringReader(html)),dom);
	
			/*
			 * Call the work horse.
			 */
			
			for(int i = 0;i<dom.getChildNodes().getLength();i++) {
				
				Node tmp = dom.getChildNodes().item(i);
				
				recursiveValidateTag(tmp);
				
				/*
				 * This check indicates if the node that was just scanned
				 * was removed/failed validation.
				 */ 
				if ( tmp.getParentNode() == null ) {
					i--;
				}
				
			}
			
			/*
			 * Serialize the output and then return the resulting
			 * DOM object and its string representation.
			 */
	        
			OutputFormat format = new OutputFormat();
			
			format.setLineWidth(80);
	        format.setIndenting(true);
	        format.setIndent(2);
	        
	        format.setEncoding(outputEncoding);
	        format.setOmitXMLDeclaration( "true".equals(policy.getDirective("omitXmlDeclaration")) );
	        format.setOmitDocumentType( "true".equals(policy.getDirective("omitDoctypeDeclaration")) );
	        format.setPreserveEmptyAttributes(true);

	        
	        StringWriter sw = new StringWriter();

	        /*
	         * Using the HTMLSerializer is the only way to notify the parser to fire
	         * events for recognizing HTML-entities. The other ways should, but do not
	         * work.
	         * 
	         * We're using HTMLSerializer even though it's deprecated.
	         *  
	         * See http://marc.info/?l=xerces-j-dev&m=108071323405980&w=2 for why we 
	         * know it's still ok to use.
	         * 
	         */

	        if ( "true".equals(policy.getDirective("useXHTML"))) {
	        	
	        	XHTMLSerializer serializer = new XHTMLSerializer(sw,format);
		        
		        serializer.serialize(dom);
		        
	        } else {
	        	
	        	HTMLSerializer serializer = new HTMLSerializer(sw,format);
		        
		        serializer.serialize(dom);
		        
	        }
	        
	
	        
	        /*
	         * Get the String out of the StringWriter and rip out the
	         * XML declaration if the Policy says we should.
	         */
	        String finalCleanHTML = sw.getBuffer().toString();
	        
	        if ( "true".equals(policy.getDirective("omitXmlDeclaration")) ) {
	        	
	        	int bpos = finalCleanHTML.indexOf("<?xml");
	        	int epos = finalCleanHTML.indexOf("?>");
	        	
	        	if ( bpos != -1 && bpos != -1 && bpos < epos ) {
	        		finalCleanHTML = finalCleanHTML.substring(epos+1);
	        	}
	        	
	        }
	        
	        /**
			 * Return DOM object as well as string HTML.
			 */

	        results = new CleanResults(start, new Date(), finalCleanHTML, dom, errorMessages );
				        
			return results;

		} catch (SAXException e) {
			throw new ScanException(e);
		} catch (IOException e) {
			throw new ScanException(e);
		}
		
	}


	/**
	 * The workhorse of the scanner. Recursively scans document elements
	 * according to the policy. This should be called implicitly through
	 * the AntiSamy.scan() method.
	 * @param node The node to validate.
	 */
	
	private void recursiveValidateTag(Node node) {
		
		if ( !(node instanceof Element) ) {
			return;
		}

		Element ele = (Element)node;
		Node parentNode = ele.getParentNode();
		Node tmp = null;
		
		/*
		 * See if we have a policy for this tag. If we do, getTagByName()
		 * will retrieve its object representation.
		 */
		
		String tagName = ele.getNodeName();
		
		Tag tag = policy.getTagByName(tagName.toLowerCase());
		
		if ( tag == null || "filter".equals(tag.getAction() )) {

			/*
			 * Describe the event. 
			 */
			StringBuffer errBuff = new StringBuffer();
			
			errBuff.append("The <b>"+HTMLEntityEncoder.htmlEntityEncode(tagName.toLowerCase()));
			errBuff.append("</b> tag has been filtered for security reasons. The contents of the tag will ");
			errBuff.append("remain in place.");
			
			errorMessages.add(errBuff.toString());

			/*
			 * We have to filter out the tags only. This means
			 * the content should remain. First step is to validate 
			 * before promoting its children.
			 */
	
			for(int i=0;i<node.getChildNodes().getLength();i++) {
				
				tmp = node.getChildNodes().item(i);
				
				recursiveValidateTag(tmp);
			
				/*
				 * This indicates the node was removed/failed validation.
				 */ 
				if ( tmp.getParentNode() == null ) {
					i--;
				}
			}
			
			/*
			 * Loop through and add the children node to the parent
			 * before removing the current node from the parent.
			 * 
			 * We must get a fresh copy of the children nodes because
			 * validating the children may have resulted in us getting 
			 * less or more children.
			 */

			promoteChildren(ele);
			
			return;
			
			
		} else if ( "validate".equals(tag.getAction()) ) {
		
			/*
			 * Check to see if it's a <style> tag. We have to special case this
			 * tag so we can hand it off to the custom style sheet validating
			 * parser.
			 */
			
			if ( "style".equals(tagName.toLowerCase()) && policy.getTagByName("style") != null  ) {
				
				/*
				 * Invoke the css parser on this element.
				 */ 
				CssScanner styleScanner = new CssScanner(policy);
				
				try {

					CleanResults cr = styleScanner.scanStyleSheet(node.getFirstChild().getNodeValue());
					
					errorMessages.addAll(cr.getErrorMessages());

					/*
					 * If IE gets an empty style tag, i.e. <style/>
					 * it will break all CSS on the page. I wish I
					 * was kidding. So, if after validation no CSS
					 * properties are left, we would normally be left
					 * with an empty style tag and break all CSS. To
					 * prevent that, we have this check.
					 */
					
					if ( cr.getCleanHTML() == null || cr.getCleanHTML().equals("") ) {

						node.getFirstChild().setNodeValue("/* */");
						
					} else {
					
						node.getFirstChild().setNodeValue(cr.getCleanHTML());
						
					}
					
				} catch (DOMException e) {
				
					errorMessages.add("The <b>style</b> tag with a value of <u>" + HTMLEntityEncoder.htmlEntityEncode(node.getFirstChild().getNodeValue()) + "</u> because it was malformed. This may affect the look of the page.");
					parentNode.removeChild(node);
					
					return;
					
				} catch (ScanException e) {
					
					errorMessages.add("The <b>style</b> tag with a value of <u>" + HTMLEntityEncoder.htmlEntityEncode(node.getFirstChild().getNodeValue()) + "</u> because it was malformed. This may affect the look of the page.");
					parentNode.removeChild(node);
					
					return;
				}
			}
			
			/*
			 * Go through the attributes in the tainted tag and validate them
			 * against the values we have for them.
			 * 
			 * If we don't have a rule for the attribute we remove the
			 * attribute.
			 */

			Node attribute = null;
			
			for(int currentAttributeIndex = 0; currentAttributeIndex < ele.getAttributes().getLength(); currentAttributeIndex++) {
				
				attribute = ele.getAttributes().item(currentAttributeIndex);
								
				String name = attribute.getNodeName();
				String value = attribute.getNodeValue();
				
				Attribute attr = tag.getAttributeByName(name);

				/**
				 * If we there isn't an attribute by that name in our policy
				 * check to see if it's a globally defined attribute. Validate
				 * against that if so.
				 */
				
				if ( attr == null ) {
					attr = policy.getGlobalAttributeByName(name);
				}
				
				boolean isAttributeValid = false;
				
				/*
				 * We have to special case the "style" attribute since it's validated quite differently.
				 */
				if ( "style".equals(name.toLowerCase()) && attr != null ) {
					
					// invoke the CSS parser on this element
					CssScanner styleScanner = new CssScanner(policy);
					
					try {
					
						CleanResults cr = styleScanner.scanInlineStyle(value,tagName);

						attribute.setNodeValue(cr.getCleanHTML());
						
						ArrayList cssScanErrorMessages = cr.getErrorMessages();
						errorMessages.addAll(cssScanErrorMessages);
												
					} catch (DOMException e) {

						errorMessages.add("The <b>" + HTMLEntityEncoder.htmlEntityEncode(tagName)+ "</b> tag had a <b>style</b> attribute with a value of <u>" + HTMLEntityEncoder.htmlEntityEncode(node.getNodeValue()) + "</u> that could not be accepted for security reasons. This may affect the look of the page.");
						ele.removeAttribute(name);
						currentAttributeIndex--;

					} catch (ScanException e) {
					
						errorMessages.add("The <b>" + HTMLEntityEncoder.htmlEntityEncode(tagName)+ "</b> tag had a <b>style</b> attribute with a value of <u>" + HTMLEntityEncoder.htmlEntityEncode(node.getNodeValue()) + "</u> that could not be accepted for security reasons. This may affect the look of the page.");
						ele.removeAttribute(name);
						currentAttributeIndex--;
					}
					
					
				} else {
				
					if ( attr != null ) {

						Iterator allowedValues = attr.getAllowedValues().iterator();
	
						while ( allowedValues.hasNext() && ! isAttributeValid ) {

							String allowedValue = (String) allowedValues.next();
	
							if (allowedValue != null && allowedValue.toLowerCase().equals(value.toLowerCase())) {
								isAttributeValid = true;
							}
						}
	
						Iterator allowedRegexps = attr.getAllowedRegExp().iterator();
						
						while (allowedRegexps.hasNext() && !isAttributeValid) {
	
							Pattern pattern = (Pattern) allowedRegexps.next();
							
							if (pattern != null && pattern.matcher(value.toLowerCase()).matches() ) {
								isAttributeValid = true;
							}	
						}
						
						if ( ! isAttributeValid ) {
						
							/*
							 * Document transgression and perform the
							 * "onInvalid" action. The default action is to
							 * strip the attribute and leave the rest intact.
							 */
						
							String onInvalidAction = attr.getOnInvalid();
							StringBuffer errBuff = new StringBuffer();
							
							errBuff.append("The <b>"+HTMLEntityEncoder.htmlEntityEncode(tagName)+"</b> tag contained an attribute that we couldn't process. ");
							errBuff.append("The <b>"+HTMLEntityEncoder.htmlEntityEncode(name)+"</b> attribute had a value of <u>"+HTMLEntityEncoder.htmlEntityEncode(value)+"</u>. ");
							errBuff.append("This value could not be accepted for security reasons. We have chosen to ");
										
							if ("removeTag".equals(onInvalidAction)) {
	
								/*
								 * Remove the tag and its contents.
								 */
								
								parentNode.removeChild(ele);
	
								errBuff.append("remove the <b>"+tagName+"</b> tag and its contents in order to process this input. ");
	
							} else if ("filterTag".equals(onInvalidAction)) {
	
								/*
								 * Remove the attribute and keep the rest of the
								 * tag.
								 */
							
								for(int i=0;i<node.getChildNodes().getLength();i++) {
									
									tmp = node.getChildNodes().item(i);
									
									recursiveValidateTag(tmp);
									
									/*
									 * This indicates the node was removed/failed validation.
									 */
									if ( tmp.getParentNode() == null ) {
										i--;
									}
								}
								
								promoteChildren(ele);
	
								errBuff.append("filter the <b>"+HTMLEntityEncoder.htmlEntityEncode(tagName)+"</b> tag and leave its contents in place so that we could process this input.");
								
							} else { 
								
								/*
								 * onInvalidAction = "removeAttribute"
								 */ 
								
								ele.removeAttribute(attr.getName());
								
								currentAttributeIndex--;
								
								errBuff.append("remove the <b>"+HTMLEntityEncoder.htmlEntityEncode(name)+"</b> attribute from the tag and leave everything else in place so that we could process this input.");
								
							}
	
							errorMessages.add(errBuff.toString());
							
							if ( "removeTag".equals(onInvalidAction) || "filterTag".equals(onInvalidAction) ) {
								return; // can't process any more if we remove/filter the tag	
							}
								
						}
					
					} else { /* the attribute they specified isn't in our policy - remove it (whitelisting!) */
	
						
						StringBuffer errBuff = new StringBuffer();
						
						errBuff.append("The <b>"+HTMLEntityEncoder.htmlEntityEncode(name));
						errBuff.append("</b> attribute of the <b>" + HTMLEntityEncoder.htmlEntityEncode(tagName) + "</b> tag has been removed for security reasons. ");
						errBuff.append("This removal should not affect the display of the HTML submitted.");
						
						errorMessages.add(errBuff.toString());
						
						ele.removeAttribute(name);
						
						currentAttributeIndex--;
						
					} // end if attribute is or is not found in policy file
					
				} // end if style.equals("name") 
					
			} // end while loop through attributes 
			
			/*
			 * Validate the children of this node.
			 */
			
			for(int i=0;i<node.getChildNodes().getLength();i++) {
			
				tmp = node.getChildNodes().item(i);
				
				recursiveValidateTag(tmp);
			
				/*
				 * This indicates the node was removed/failed validation.
				 */
				if ( tmp.getParentNode() == null ) {
					i--;
				}
			}
			
		} else if ( "truncate".equals(tag.getAction()) ) {
			
			/*
			 * Remove all attributes. This is for tags like
			 * i, b, u, etc. Purely formatting without
			 * any need for attributes. It also removes any
			 * children.
			 */
			
			NamedNodeMap nnmap = ele.getAttributes();
			
			while( nnmap.getLength() > 0 ) { 
				
				StringBuffer errBuff = new StringBuffer();
				
				errBuff.append("The <b>"+HTMLEntityEncoder.htmlEntityEncode(nnmap.item(0).getNodeName()));
				errBuff.append("</b> attribute of the <b>" + HTMLEntityEncoder.htmlEntityEncode(tagName) + "</b> tag has been removed for security reasons. ");
				errBuff.append("This removal should not affect the display of the HTML submitted.");
				
				ele.removeAttribute(nnmap.item(0).getNodeName());
				
				errorMessages.add(errBuff.toString());
			}
			
			NodeList cList = ele.getChildNodes();
			
			int i = 0;
			int j = 0;
			int length = cList.getLength();
			
			while ( i < length ) {
				
				Node nodeToRemove = cList.item(j);
				
				if ( nodeToRemove.getNodeType() != Node.TEXT_NODE  && nodeToRemove.getNodeType() != Node.COMMENT_NODE ) {
					ele.removeChild(nodeToRemove);
				} else {
					j++;
				}
				
				i++;
			}
			
			
		} else {
			
			/*
			 * If we reached this that means that the tag's action
			 * is "remove", which means to remove the tag (including
			 * its contents).
			 */
	
			errorMessages.add("The <b>"+HTMLEntityEncoder.htmlEntityEncode(tagName)+"</b> tag has been removed for security reasons.");
			
			parentNode.removeChild(ele);
			
		}

	}

	

	/**
	 * This method replaces all entity codes with a normalized version of all entity references contained in order to reduce our encoding/parsing
	 * attack surface.
	 * @param txt The string to be normalized.
	 * @return The normalized version of the string.
	 */
	/*
	private String replaceEntityCodes(String txt) {

		if ( txt == null ) {
			return null;
		}
		
		String entityPattern = "&[a-zA-Z0-9]{2,};";
		Pattern pattern = Pattern.compile(entityPattern);
		Matcher matcher = pattern.matcher(txt);
		StringBuffer buff = new StringBuffer();
	
		int lastIndex = 0;
		
		while ( matcher.find() ) {
				
			String entity = matcher.group();
			int startPos = matcher.start();
			int endPos = matcher.end();
		
			entity = entity.substring(1);
			entity = entity.substring(0,entity.length()-1);
			
			String code = policy.getEntityReferenceCode(entity);
			
			if ( code != null ) {
				
				buff.append(txt.substring(lastIndex,startPos));
				buff.append(code);
				lastIndex = endPos;

			}

		}
		
		buff.append(txt.substring(lastIndex));
		
		return buff.toString();
		
	}
	*/
	
	public static void main(String[] args) throws PolicyException {
		
		Date start = new Date();
		
		//System.out.println( new AntiSamyDOMScanner().replaceEntityCodes ("This is &nbsp;&nbsp;, so &iexcl; omfg sdf &infg;") );

		Date end = new Date();
		
		System.out.println ( (end.getTime() - start.getTime()) / 1000D );
	}

	public AntiSamyDOMScanner(Policy policy) {
		this.policy = policy;
	}
	
	public AntiSamyDOMScanner() throws PolicyException {
		this.policy = Policy.getInstance();
	}


	public CleanResults getResults() {
		return results;
	}

	public void setResults(CleanResults results) {
		this.results = results;
	}


	/**
	 * Used to promote the children of a parent to accomplish the "filterTag" action.
	 * @param ele The Element we want to filter.
	 */
	private void promoteChildren(Element ele) {
		
		NodeList nodeList = ele.getChildNodes();
		Node parent = ele.getParentNode();

		while ( nodeList.getLength() > 0) {
			Node node = ele.removeChild(nodeList.item(0));
			parent.insertBefore(node,ele);
		}

		parent.removeChild(ele);

	}
	
	/**
	 * 
	 * This method was borrowed from Mark McLaren, to whom I owe much beer.
	 * 
     * This method ensures that the output String has only
     * valid XML unicode characters as specified by the
     * XML 1.0 standard. For reference, please see
     * <a href="http://www.w3.org/TR/2000/REC-xml-20001006#NT-Char">the
     * standard</a>. This method will return an empty
     * String if the input is null or empty.
     *
     * @param in The String whose non-valid characters we want to remove.
     * @return The in String, stripped of non-valid characters.
     */
    private String stripNonValidXMLCharacters(String in) {
        
    	StringBuffer out = new StringBuffer(); // Used to hold the output.
        
    	char current; // Used to reference the current character.

        if (in == null || ("".equals(in))) return ""; // vacancy test.
        for (int i = 0; i < in.length(); i++) {
            current = in.charAt(i); // NOTE: No IndexOutOfBoundsException caught here; it should not happen.
            if ((current == 0x9) ||
                (current == 0xA) ||
                (current == 0xD) ||
                ((current >= 0x20) && (current <= 0xD7FF)) ||
                ((current >= 0xE000) && (current <= 0xFFFD)) ||
                ((current >= 0x10000) && (current <= 0x10FFFF)))
                out.append(current);
        }
        
        return out.toString();
    
    }
    
	//private void debug(String s) { System.out.println(s); }
	
}
