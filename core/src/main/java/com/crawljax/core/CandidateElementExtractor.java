package com.crawljax.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPathExpressionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.condition.eventablecondition.EventableCondition;
import com.crawljax.condition.eventablecondition.EventableConditionChecker;
import com.crawljax.core.configuration.CrawlAttribute;
import com.crawljax.core.configuration.CrawlElement;
import com.crawljax.core.configuration.CrawljaxConfiguration;
import com.crawljax.core.configuration.InputSpecification;
import com.crawljax.core.configuration.PreCrawlConfiguration;
import com.crawljax.core.state.Identification;
import com.crawljax.core.state.StateVertex;
import com.crawljax.forms.FormHandler;
import com.crawljax.util.DomUtils;
import com.crawljax.util.UrlUtils;
import com.crawljax.util.XPathHelper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;

/**
 * This class extracts candidate elements from the DOM tree, based on the tags provided by the user.
 * Elements can also be excluded.
 */
public class CandidateElementExtractor {

	private static final Logger LOG = LoggerFactory.getLogger(CandidateElementExtractor.class);

	private final ExtractorManager checkedElements;
	private final EmbeddedBrowser browser;

	private final FormHandler formHandler;
	private final boolean crawlFrames;
	private final ImmutableMultimap<String, CrawlElement> excludeCrawlElements;
	private final ImmutableList<CrawlElement> includedCrawlElements;

	private final boolean clickOnce;

	private ImmutableSortedSet<String> ignoredFrameIdentifiers;

	/**
	 * Create a new CandidateElementExtractor.
	 * 
	 * @param checker
	 *            the ExtractorManager to use for marking handled elements and retrieve the
	 *            EventableConditionChecker
	 * @param browser
	 *            the current browser instance used in the Crawler
	 * @param formHandler
	 *            the form handler.
	 * @param config
	 *            the checker used to determine if a certain frame must be ignored.
	 */
	public CandidateElementExtractor(ExtractorManager checker, EmbeddedBrowser browser,
	        FormHandler formHandler, CrawljaxConfiguration config) {
		checkedElements = checker;
		this.browser = browser;
		this.formHandler = formHandler;
		PreCrawlConfiguration preCrawlConfig = config.getCrawlRules().getPreCrawlConfig();
		this.excludeCrawlElements = asMultiMap(preCrawlConfig.getExcludedElements());
		this.includedCrawlElements =
		        asCrawlElements(preCrawlConfig.getIncludedElements(), config.getCrawlRules()
		                .getInputSpecification());
		crawlFrames = config.getCrawlRules().shouldCrawlFrames();
		clickOnce = config.getCrawlRules().isClickOnce();
		ignoredFrameIdentifiers = config.getCrawlRules().getIgnoredFrameIdentifiers();
	}

	private ImmutableMultimap<String, CrawlElement> asMultiMap(ImmutableList<CrawlElement> elements) {
		ImmutableMultimap.Builder<String, CrawlElement> builder = ImmutableMultimap.builder();
		for (CrawlElement elem : elements) {
			CrawlElement crawlElement = new CrawlElement(elem);
			builder.put(crawlElement.getTagName(), crawlElement);
		}
		return builder.build();
	}

	private ImmutableList<CrawlElement> asCrawlElements(List<CrawlElement> crawlElements,
	        InputSpecification inputSpecification) {
		ImmutableList.Builder<CrawlElement> builder = ImmutableList.builder();
		for (CrawlElement crawlElement : crawlElements) {
			builder.add(new CrawlElement(crawlElement));
		}
		for (CrawlElement crawlElement : inputSpecification.getCrawlElements()) {
			builder.add(new CrawlElement(crawlElement));
		}
		return builder.build();
	}

	/**
	 * This method extracts candidate elements from the current DOM tree in the browser, based on
	 * the crawl tags defined by the user.
	 * 
	 * @param crawlTagElements
	 *            a list of TagElements to include.
	 * @param crawlExcludeTagElements
	 *            a list of TagElements to exclude.
	 * @param clickOnce
	 *            true if each candidate elements should be included only once.
	 * @param currentState
	 *            the state in which this extract method is requested.
	 * @return a list of candidate elements that are not excluded.
	 * @throws CrawljaxException
	 *             if the method fails.
	 */
	public ImmutableList<CandidateElement> extract(StateVertex currentState)
	        throws CrawljaxException {
		Builder<CandidateElement> results = ImmutableList.builder();

		if (!checkedElements.checkCrawlCondition(browser)) {
			LOG.info("State {} did not satisfy the CrawlConditions.", currentState.getName());
			return results.build();
		}
		LOG.debug("Looking in state: {} for candidate elements", currentState.getName());

		try {
			Document dom = DomUtils.asDocument(browser.getDomWithoutIframeContent());
			extractElements(dom, results, "");
		} catch (IOException e) {
			LOG.error(e.getMessage(), e);
			throw new CrawljaxException(e);
		}
		ImmutableList<CandidateElement> found = results.build();
		LOG.debug("Found {} new candidate elements to analyze!", found.size());
		return found;
	}

	private void extractElements(Document dom, Builder<CandidateElement> results,
	        String relatedFrame) {
		LOG.debug("Extracting elements for related frame '{}'", relatedFrame);
		for (CrawlElement tag : includedCrawlElements) {
			LOG.debug("Extracting TAG: {}", tag);

			NodeList frameNodes = dom.getElementsByTagName("FRAME");
			addFramesCandidates(dom, results, relatedFrame, frameNodes);

			NodeList iFrameNodes = dom.getElementsByTagName("IFRAME");
			addFramesCandidates(dom, results, relatedFrame, iFrameNodes);

			eveluateElements(dom, tag, results, relatedFrame);
		}
	}

	private void addFramesCandidates(Document dom, Builder<CandidateElement> results,
	        String relatedFrame, NodeList frameNodes) {

		if (frameNodes == null) {
			return;
		}

		for (int i = 0; i < frameNodes.getLength(); i++) {

			String frameIdentification = "";

			if (relatedFrame != null && !relatedFrame.equals("")) {
				frameIdentification += relatedFrame + ".";
			}

			Element frameElement = (Element) frameNodes.item(i);

			String nameId = DomUtils.getFrameIdentification(frameElement);

			// TODO Stefan; Here the IgnoreFrameChecker is used, also in
			// WebDriverBackedEmbeddedBrowser. We must get this in 1 place.
			if (nameId == null || isFrameIgnored(frameIdentification + nameId)) {
				continue;
			} else {
				frameIdentification += nameId;

				LOG.debug("frame Identification: {}", frameIdentification);

				try {
					Document frameDom =
					        DomUtils.asDocument(browser.getFrameDom(frameIdentification));
					extractElements(frameDom, results, frameIdentification);
				} catch (IOException e) {
					LOG.info("Got exception while inspecting a frame: {} continuing...",
					        frameIdentification, e);
				}
			}
		}
	}

	private boolean isFrameIgnored(String string) {
		if (crawlFrames) {
			for (String ignorePattern : ignoredFrameIdentifiers) {
				if (ignorePattern.contains("%")) {
					// replace with a useful wildcard for regex
					String pattern = ignorePattern.replace("%", ".*");
					if (string.matches(pattern)) {
						return true;
					}
				} else if (ignorePattern.equals(string)) {
					return true;
				}
			}
			return false;
		} else {
			return true;
		}
	}

	private void eveluateElements(Document dom, CrawlElement tag,
	        Builder<CandidateElement> results, String relatedFrame) {
		try {
			List<Element> nodeListForTagElement =
			        getNodeListForTagElement(dom, tag,
			                checkedElements.getEventableConditionChecker());

			for (Element sourceElement : nodeListForTagElement) {
				evaluateElement(results, relatedFrame, tag, sourceElement);
			}
		} catch (CrawljaxException e) {
			LOG.warn("Catched exception during NodeList For Tag Element retrieval", e);
		}
	}

	/**
	 * Returns a list of Elements form the DOM tree, matching the tag element.
	 */
	private ImmutableList<Element> getNodeListForTagElement(Document dom, CrawlElement tag,
	        EventableConditionChecker eventableConditionChecker) {

		Builder<Element> result = ImmutableList.builder();

		if (tag.getTagName() == null) {
			return result.build();
		}

		EventableCondition eventableCondition =
		        eventableConditionChecker.getEventableCondition(tag.getId());
		// TODO Stefan; this part of the code should be re-factored, Hack-ed it this way to prevent
		// performance problems.
		ImmutableList<String> expressions = getFullXpathForGivenXpath(dom, eventableCondition);

		NodeList nodeList = dom.getElementsByTagName(tag.getTagName());
		ImmutableList<CrawlAttribute> attributes = tag.getCrawlAttributes();

		for (int k = 0; k < nodeList.getLength(); k++) {

			Element element = (Element) nodeList.item(k);
			boolean matchesXpath =
			        elementMatchesXpath(eventableConditionChecker, eventableCondition,
			                expressions, element);
			LOG.debug("Element {} matches Xpath={}", DomUtils.getElementString(element),
			        matchesXpath);
			/*
			 * TODO Stefan This is a possible Thread-Interleaving problem, as / isChecked can return
			 * false and when needed to add it can return true. / check if element is a candidate
			 */
			String id = element.getNodeName() + ": " + DomUtils.getAllElementAttributes(element);
			if (matchesXpath && !checkedElements.isChecked(id)
			        && !filterElement(attributes, element)
			        && !isExcluded(dom, element, eventableConditionChecker)) {
				addElement(element, result, tag);
			} else {
				LOG.debug("Element {} was not added", element);
			}
		}
		return result.build();
	}

	private boolean elementMatchesXpath(EventableConditionChecker eventableConditionChecker,
	        EventableCondition eventableCondition, ImmutableList<String> expressions,
	        Element element) {
		boolean matchesXpath = true;
		if (eventableCondition != null && eventableCondition.getInXPath() != null) {
			try {
				matchesXpath =
				        eventableConditionChecker.checkXPathUnderXPaths(
				                XPathHelper.getXPathExpression(element), expressions);
			} catch (RuntimeException e) {
				matchesXpath = false;
			}
		}
		return matchesXpath;
	}

	private ImmutableList<String> getFullXpathForGivenXpath(Document dom,
	        EventableCondition eventableCondition) {
		if (eventableCondition != null && eventableCondition.getInXPath() != null) {
			try {
				ImmutableList<String> result =
				        XPathHelper.getXpathForXPathExpressions(dom,
				                eventableCondition.getInXPath());
				LOG.debug("Xpath {} resolved to xpaths in document: {}",
				        eventableCondition.getInXPath(), result);
				return result;
			} catch (XPathExpressionException e) {
				LOG.debug("Could not load XPath expressions for {}", eventableCondition, e);
			}
		}
		return ImmutableList.<String> of();
	}

	private void addElement(Element element, Builder<Element> builder, CrawlElement crawlElement) {
		if ("A".equalsIgnoreCase(crawlElement.getTagName())) {
			String href = element.getAttribute("href");
			if (!Strings.isNullOrEmpty(href)) {
				boolean isExternal = UrlUtils.isLinkExternal(browser.getCurrentUrl(), href);
				LOG.debug("HREF: {} isExternal= {}", href, isExternal);
				if (isExternal || isPDForPS(href)) {
					return;
				}
			}
		}
		builder.add(element);
		LOG.debug("Adding element {}", element);
		checkedElements.increaseElementsCounter();
	}

	private void evaluateElement(Builder<CandidateElement> results, String relatedFrame,
	        CrawlElement tag, Element sourceElement) {
		EventableCondition eventableCondition =
		        checkedElements.getEventableConditionChecker().getEventableCondition(tag.getId());
		String xpath = XPathHelper.getXPathExpression(sourceElement);
		// get multiple candidate elements when there are input
		// fields connected to this element

		List<CandidateElement> candidateElements = new ArrayList<CandidateElement>();
		if (eventableCondition != null && eventableCondition.getLinkedInputFields() != null
		        && eventableCondition.getLinkedInputFields().size() > 0) {
			// add multiple candidate elements, for every input
			// value combination
			candidateElements =
			        formHandler.getCandidateElementsForInputs(sourceElement, eventableCondition);
		} else {
			// just add default element
			candidateElements.add(new CandidateElement(sourceElement, new Identification(
			        Identification.How.xpath, xpath), relatedFrame));
		}

		for (CandidateElement candidateElement : candidateElements) {
			if (!clickOnce || checkedElements.markChecked(candidateElement)) {
				LOG.debug("Found new candidate element: {} with eventableCondition {}",
				        candidateElement.getUniqueString(), eventableCondition);
				candidateElement.setEventableCondition(eventableCondition);
				results.add(candidateElement);
				/**
				 * TODO add element to checkedElements after the event is fired! also add string
				 * without 'atusa' attribute to make sure an form action element is only clicked for
				 * its defined values
				 */
			}
		}
	}

	/**
	 * @param href
	 *            the string to check
	 * @return true if href has the pdf or ps pattern.
	 */
	private boolean isPDForPS(String href) {
		final Pattern p = Pattern.compile(".+.pdf|.+.ps");
		Matcher m = p.matcher(href);

		if (m.matches()) {
			return true;
		}

		return false;
	}

	/**
	 * @return true if element should be excluded. Also when an ancestor of the given element is
	 *         marked for exclusion, which allows for recursive exclusion of elements from
	 *         candidates.
	 */
	private boolean isExcluded(Document dom, Element element,
	        EventableConditionChecker eventableConditionChecker) {

		Node parent = element.getParentNode();

		if (parent instanceof Element
		        && isExcluded(dom, (Element) parent, eventableConditionChecker)) {
			return true;
		}

		for (CrawlElement tag : excludeCrawlElements.get(element.getTagName().toUpperCase())) {
			boolean matchesXPath = false;
			EventableCondition eventableCondition =
			        eventableConditionChecker.getEventableCondition(tag.getId());
			try {
				String asXpath = XPathHelper.getXPathExpression(element);
				matchesXPath =
				        eventableConditionChecker.checkXpathStartsWithXpathEventableCondition(
				                dom, eventableCondition, asXpath);
			} catch (CrawljaxException | XPathExpressionException e) {
				LOG.debug("Could not check exclusion by Xpath for element because {}",
				        e.getMessage());
				matchesXPath = false;
			}

			if (matchesXPath) {
				LOG.info("Excluded element because of xpath: " + element);
				return true;
			}
			if (!filterElement(tag.getCrawlAttributes(), element) && tag.getCrawlAttributes().size() > 0) {
				LOG.info("Excluded element because of attributes: " + element);
				return true;
			}
		}

		return false;
	}

	/**
	 * Return whether the element is filtered out because of its attributes.
	 */
	private boolean filterElement(ImmutableList<CrawlAttribute> immutableList, Element element) {
		int matchCounter = 0;
		if (element == null || immutableList == null) {
			return false;
		}
		for (CrawlAttribute attr : immutableList) {
			LOG.debug("Checking element " + DomUtils.getElementString(element)
			        + "AttributeName: " + attr.getName() + " value: " + attr.getValue());

			if (attr.matchesValue(element.getAttribute(attr.getName()))) {
				// make sure that if attribute value is % the element should
				// have this attribute
				if (attr.getValue().equals("%")
				        && element.getAttributeNode(attr.getName()) == null) {
					return true;
				} else {
					matchCounter++;
				}
			} else if (attr.getName().equalsIgnoreCase("innertext")
			        && element.getTextContent() != null) {
				String value = attr.getValue();
				String text = element.getTextContent().trim();
				if (value.contains("%")) {
					String pattern = value.replace("%", "(.*?)");
					if (text.matches(pattern)) {
						matchCounter++;
					}

				} else if (text.equalsIgnoreCase(value)) {
					matchCounter++;
				}
			}

		}

		return (immutableList.size() != matchCounter);
	}
}
