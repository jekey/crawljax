package com.crawljax.plugins.clickable_detector;

import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.browser.WebDriverBrowserBuilder;
import com.crawljax.core.configuration.CrawljaxConfiguration;
import com.crawljax.core.configuration.ProxyConfiguration;
import com.crawljax.core.plugin.Plugins;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

@RunWith(MockitoJUnitRunner.class)
public class ProxyTest {


	@Mock
	public Plugins plugins;
	private HttpProxyServer server;
	private AtomicInteger interceptorCount = new AtomicInteger();

	@Before
	public void setup() {
		server = DefaultHttpProxyServer.bootstrap()
		  .withPort(8080)
		  .withFiltersSource(new HttpFiltersSourceAdapter() {


			  @Override
			  public HttpFilters filterRequest(HttpRequest originalRequest) {

				  return new HttpFiltersAdapter(originalRequest) {

					  @Override
					  public void responsePost(HttpObject httpObject) {
							interceptorCount.incrementAndGet();
					  }
				  };
			  }
		  })
		  .start();
	}

	@Test
	public void testProxyPassThrough() throws MalformedURLException {
		CrawljaxConfiguration conf = CrawljaxConfiguration.builderFor("http://demo.crawljax.com")
		  .setProxyConfig(ProxyConfiguration.manualProxyOn("127.0.0.1", 8080))
		  .build();
		WebDriverBrowserBuilder builder = new WebDriverBrowserBuilder(conf, plugins);
		EmbeddedBrowser browser = builder.get();
		browser.goToUrl(new URL("http://demo.crawljax.com"));
		browser.close();
		assertThat(interceptorCount.get(), is(greaterThan(2)));
	}

	@After
	public void destroy() {
		server.stop();
	}

}
