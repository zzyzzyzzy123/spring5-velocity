package org.apache.velocity.tools.view.tools;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.tools.generic.ValueParser;
import org.apache.velocity.tools.view.context.ViewContext;
import org.apache.velocity.tools.view.servlet.ServletUtils;

/**
 * View tool to make building URIs pleasant and fun!
 * <p><pre>
 * Template example(s):
 *   #set( $base = $link.relative('MyPage.vm').anchor('view') )
 *   &lt;a href="$base.param('select','this')"&gt;this&lt;/a&gt;
 *   &lt;a href="$base.param('select','that')"&gt;that&lt;/a&gt;
 *
 * Toolbox configuration:
 * &lt;tool&gt;
 *   &lt;key&gt;link&lt;/key&gt;
 *   &lt;scope&gt;request&lt;/scope&gt;
 *   &lt;class&gt;org.apache.velocity.tools.view.tools.LinkTool&lt;/class&gt;
 * &lt;/tool&gt;
 * </pre></p>
 *
 * <p>This tool should only be used in the request scope.</p>
 *
 * @author <a href="mailto:sidler@teamup.com">Gabe Sidler</a>
 * @author Nathan Bubna
 * @author Chris Schultz
 * @since VelocityTools 1.0
 * @version $Id$
 */
public class LinkTool implements Cloneable
{
    protected static final Log LOG = LogFactory.getLog(LinkTool.class);

    /**
     * Parameter key for configuring {@link #setSelfAbsolute} state
     * @since VelocityTools 1.3
     */
    public static final String SELF_ABSOLUTE_KEY = "self-absolute";

    /**
     * Parameter key for configuring {@link #setSelfIncludeParameters} state
     * @since VelocityTools 1.3
     */
    public static final String SELF_INCLUDE_PARAMETERS_KEY = "self-include-parameters";

    /**
     * Parameter key for configuring {@link #setAutoIgnoreParameters} state.
     * @since VelocityTools 1.4
     */
    public static final String AUTO_IGNORE_PARAMETERS_KEY = "auto-ignore-parameters";

    /** Standard HTML delimiter for query data ('&') */
    public static final String HTML_QUERY_DELIMITER = "&";

    /** XHTML delimiter for query data ('&amp;amp;') */
    public static final String XHTML_QUERY_DELIMITER = "&amp;";


    /** A reference to the ServletContext */
    protected ServletContext application;

    /** A reference to the HttpServletRequest. */
    protected HttpServletRequest request;

    /** A reference to the HttpServletResponse. */
    protected HttpServletResponse response;


    /** The URI reference set for this link. */
    private String uri;

    /** The anchor set for this link. */
    private String anchor;

    /** A list of query string parameters. */
    private ArrayList queryData;

    /** The current delimiter for query data */
    private String queryDataDelim;

    /** The self-absolute status */
    private boolean selfAbsolute;

    /** The self-include-parameters status */
    private boolean selfParams;

    /**
     * List of parameters that should be ignored when the current request's
     * parameters are copied.
     *
     * @see #addIgnore(String)
     * @see #addAllParameters()
     */
    private HashSet parametersToIgnore;

    /**
     * <code>true</code> if parameters explicitly set on this LinkTool
     * should be added to the ignore list.
     *
     * @see #parametersToIgnore
     */
    private boolean autoIgnore = true;

    /** Java 1.4 encode method to use instead of deprecated 1.3 version. */
    private static Method encode = null;

    /* Initialize the encode variable with the 1.4 method if available.
     * this code was adapted from org.apache.struts.utils.RequestUtils */
    static
    {
        try
        {
            /* get version of encode method with two String args  */
            Class[] args = new Class[] { String.class, String.class };
            encode = URLEncoder.class.getMethod("encode", args);
        }
        catch (NoSuchMethodException e)
        {
            LOG.debug("Can't find JDK 1.4 encode method. Using JDK 1.3 version.");
        }
    }


    /**
     * Default constructor. Tool must be initialized before use.
     */
    public LinkTool()
    {
        uri = null;
        anchor = null;
        queryData = null;
        queryDataDelim = HTML_QUERY_DELIMITER;
        selfAbsolute = false;
        selfParams = false;
    }


    // --------------------------------------- Protected Methods -------------

    /**
     * <p>Controls the delimiter used for separating query data pairs.
     *    By default, the standard '&' character is used.</p>
     * <p>This is not exposed to templates as this decision is best not
     *    made at that level.</p>
     * <p>Subclasses may easily override the init() method to set this
     *    appropriately and then call super.init()</p>
     *
     * @param useXhtml if true, the XHTML query data delimiter ('&amp;amp;')
     *        will be used.  if false, then '&' will be used.
     * @see <a href="http://www.w3.org/TR/xhtml1/#C_12">Using Ampersands in Attribute Values (and Elsewhere)</a>
     */
    protected void setXhtml(boolean useXhtml)
    {
        queryDataDelim =
            (useXhtml) ? XHTML_QUERY_DELIMITER : HTML_QUERY_DELIMITER;
    }

    /**
     * <p>Controls whether or not the {@link #getSelf()} method will return
     *    a duplicate with a URI in absolute or relative form.</p>
     *
     * @param selfAbsolute if true, the {@link #getSelf()} method will return
     *        a duplicate of this tool with an absolute self-referencing URI;
     *        if false, a duplicate with a relative self-referencing URI will
     *        be returned
     * @see #getSelf()
     * @since VelocityTools 1.3
     */
    protected void setSelfAbsolute(boolean selfAbsolute)
    {
        this.selfAbsolute = selfAbsolute;
    }

    /**
     * <p>Controls whether or not the {@link #getSelf()} method will return
     *    a duplicate that includes current request parameters.</p>
     *
     * @param selfParams if true, the {@link #getSelf()} method will return
     *        a duplicate of this tool that includes current request parameters
     * @see #getSelf()
     * @since VelocityTools 1.3
     */
    protected void setSelfIncludeParameters(boolean selfParams)
    {
        this.selfParams = selfParams;
    }

    /**
     * <p>Controls whether or not the {@link #addQueryData(String,Object)}
     * and {@link #addQueryData(Map)} methods will
     * automatically add the specified parameter(s) to the ignore list
     * for {@link #addAllParameters()}</p>
     *
     * <p>The default for this setting is <code>true</code>.</p>
     *
     * @param autoIgnore if true, the {@link #addQueryData(String,Object)}
     *     and {@link #addQueryData(Map)} methods will
     *     automatically add the specified parameter(s) to the ignore list
     *     for {@link #addAllParameters()}; otherwise,
     *     {@link #addIgnore(String)} must be called explicitly for each
     *     parameter to ignore.</p>
     *
     * @see #addIgnore(String)
     * @see #addAllParameters()
     *
     * @since VelocityTools 1.4
     */
    protected void setAutoIgnoreParameters(boolean autoIgnore)
    {
        this.autoIgnore = autoIgnore;
    }

    /**
     * For internal use.
     *
     * Copies 'that' LinkTool into this one and adds the new query data.
     *
     * @param pair the query parameter to add
     */
    protected LinkTool copyWith(QueryPair pair)
    {
        LinkTool copy = duplicate();
        if (copy.queryData != null)
        {
            // set the copy's query data to a shallow clone of
            // the current query data array
            copy.queryData = (ArrayList)this.queryData.clone();
        }
        else
        {
            copy.queryData = new ArrayList();
        }
        //add new pair to this LinkTool's query data
        copy.queryData.add(pair);

        // Respect autoIgnore setting
        if(copy.autoIgnore)
        {
            if(copy.parametersToIgnore != null)
            {
                // Only clone the ignore list if we're actually going
                // to modify it.
                if(!copy.parametersToIgnore.contains(pair.getKey()))
                {
                    copy.parametersToIgnore = (HashSet)this.parametersToIgnore.clone();
                    copy.parametersToIgnore.add(pair.getKey());
                }
            }
            else
            {
                copy.parametersToIgnore = new HashSet();
                copy.parametersToIgnore.add(pair.getKey());
            }
        }

        return copy;
    }


    /**
     * For internal use.
     *
     * Copies 'that' LinkTool into this one and adds the new query data.
     *
     * @param newQueryData the query parameters to add
     * @since VelocityTools 1.3
     */
    protected LinkTool copyWith(Map newQueryData)
    {
        LinkTool copy = duplicate();
        if (copy.queryData != null)
        {
            // set the copy's query data to a shallow clone of
            // the current query data array
            copy.queryData = (ArrayList)this.queryData.clone();
        }
        else
        {
            copy.queryData = new ArrayList();
        }

        boolean clonedIgnore = false;

        for (Iterator i = newQueryData.keySet().iterator(); i.hasNext(); )
        {
            Object key = i.next();
            Object value = newQueryData.get(key);
            copy.queryData.add(new QueryPair(String.valueOf(key), value));

            if(copy.autoIgnore)
            {
                if(copy.parametersToIgnore != null)
                {
                    // Only clone the ignore list if we're actually going
                    // to modify it.
                    if(!copy.parametersToIgnore.contains(key))
                    {
                        if(!clonedIgnore)
                        {
                            copy.parametersToIgnore
                                = (HashSet)this.parametersToIgnore.clone();

                            clonedIgnore = true;
                        }

                        copy.parametersToIgnore.add(key);
                    }
                }
                else
                {
                    copy.parametersToIgnore = new HashSet();
                    copy.parametersToIgnore.add(key);
                    clonedIgnore = true;
                }
            }
        }

        return copy;
    }


    /**
     * For internal use.
     *
     * Copies 'that' LinkTool into this one and sets the new URI.
     *
     * @param uri uri string
     */
    protected LinkTool copyWith(String uri)
    {
        LinkTool copy = duplicate();
        copy.uri = uri;
        return copy;
    }


    /**
     * For internal use.
     *
     * Copies 'that' LinkTool into this one and sets the new
     * anchor for the link.
     *
     * @param anchor URI string
     */
    protected LinkTool copyWithAnchor(String anchor)
    {
        LinkTool copy = duplicate();
        copy.anchor = anchor;
        return copy;
    }


    /**
     * For internal use.
     *
     * Copies this LinkTool and adds the specified parameter name to the
     * ignore list in the copy.
     *
     * @param parameterName The name of the parameter to ignore when
     *                      copying all parameters from the current request.
     * @return A new LinkTool with the specified parameterName added to the
     *         ignore list.
     * @see #addAllParameters()
     * @see #addIgnore(String)
     */
    protected LinkTool copyWithIgnore(String parameterName)
    {
        LinkTool copy = duplicate();

        if(copy.parametersToIgnore == null)
        {
            copy.parametersToIgnore = new HashSet();
        }
        else
        {
            copy.parametersToIgnore = (HashSet)parametersToIgnore.clone();
        }

        copy.parametersToIgnore.add(parameterName);

        return copy;
    }


    /**
     * This is just to avoid duplicating this code for both copyWith() methods
     */
    protected LinkTool duplicate()
    {
        try
        {
            return (LinkTool)this.clone();
        }
        catch (CloneNotSupportedException e)
        {
            LOG.warn("Could not properly clone " + getClass() + " - " + e);

            // "clone" manually
            LinkTool copy;
            try
            {
                // one last try for a subclass instance...
                copy = (LinkTool)getClass().newInstance();
            }
            catch (Exception ee)
            {
                // fine, we'll use the base class
                copy = new LinkTool();
            }
            copy.application = this.application;
            copy.request = this.request;
            copy.response = this.response;
            copy.uri = this.uri;
            copy.anchor = this.anchor;
            copy.queryData = this.queryData;
            copy.queryDataDelim = this.queryDataDelim;
            copy.selfAbsolute = this.selfAbsolute;
            copy.selfParams = this.selfParams;
            copy.autoIgnore = this.autoIgnore;
            copy.parametersToIgnore = this.parametersToIgnore;

            return copy;
        }
    }


    // --------------------------------------- Toolbox Methods -------------

    /**
     * Configures this tool
     *
     * @param params Map of configuration parameters
     * @since VelocityTools 1.3
     */
    public void configure(Map params)
    {
        ValueParser parser = new ValueParser(params);
        Boolean selfAbsolute = parser.getBoolean(SELF_ABSOLUTE_KEY);
        if (selfAbsolute != null)
        {
            setSelfAbsolute(selfAbsolute.booleanValue());
        }
        Boolean selfParams = parser.getBoolean(SELF_INCLUDE_PARAMETERS_KEY);
        if (selfParams != null)
        {
            setSelfIncludeParameters(selfParams.booleanValue());
        }
        Boolean autoIgnoreParams = parser.getBoolean(AUTO_IGNORE_PARAMETERS_KEY);
        if (autoIgnoreParams != null)
        {
            setAutoIgnoreParameters(autoIgnoreParams.booleanValue());
        }
    }

    /**
     * Initializes this tool.
     *
     * @param obj the current ViewContext
     * @throws IllegalArgumentException if the param is not a ViewContext
     */
    public void init(Object obj)
    {
        if (!(obj instanceof ViewContext))
        {
            throw new IllegalArgumentException("Tool can only be initialized with a ViewContext");
        }

        ViewContext context = (ViewContext)obj;
        this.request = context.getRequest();
        this.response = context.getResponse();
        this.application = context.getServletContext();
        Boolean b = (Boolean)context.getAttribute(ViewContext.XHTML);
        if (b != null)
        {
            setXhtml(b.booleanValue());
        }
    }


    // --------------------------------------------- Template Methods -----------

    /**
     * <p>Returns a copy of the link with the specified anchor to be
     *    added to the end of the generated hyperlink.</p>
     *
     * Example:<br>
     * <code>&lt;a href='$link.setAnchor("foo")'&gt;Foo&lt;/a&gt;</code><br>
     * produces something like</br>
     * <code>&lt;a href="#foo"&gt;Foo&lt;/a&gt;</code><br>
     *
     * @param anchor an internal document reference
     *
     * @return a new instance of LinkTool with the set anchor
     */
    public LinkTool setAnchor(String anchor)
    {
        return copyWithAnchor(anchor);
    }

    /**
     * Convenience method equivalent to {@link #setAnchor}.
     * @since VelocityTools 1.3
     */
    public LinkTool anchor(String anchor)
    {
        return setAnchor(anchor);
    }

    /**
     * Returns the anchor (internal document reference) set for this link.
     */
    public String getAnchor()
    {
        return anchor;
    }


    /**
     * <p>Returns a copy of the link with the specified context-relative
     * URI reference converted to a server-relative URI reference. This
     * method will overwrite any previous URI reference settings but will
     * copy the query string.</p>
     *
     * Example:<br>
     * <code>&lt;a href='$link.setRelative("/templates/login/index.vm")'&gt;Login Page&lt;/a&gt;</code><br>
     * produces something like</br>
     * <code>&lt;a href="/myapp/templates/login/index.vm"&gt;Login Page&lt;/a&gt;</code><br>
     *
     * @param uri A context-relative URI reference. A context-relative URI
     * is a URI that is relative to the root of this web application.
     *
     * @return a new instance of LinkTool with the specified URI
     */
    public LinkTool setRelative(String uri)
    {
        String ctxPath = request.getContextPath();
        /* if the context path is the webapp root */
        if (ctxPath.equals("/"))
        {
            /* then don't append anything for it */
            ctxPath = "";
        }
        if (uri.startsWith("/"))
        {
            return copyWith(ctxPath + uri);
        }
        else
        {
            return copyWith(ctxPath + '/' + uri);
        }
    }

    /**
     * Convenience method equivalent to {@link #setRelative}.
     * @since VelocityTools 1.3
     */
    public LinkTool relative(String uri)
    {
        return setRelative(uri);
    }


    /**
     * <p>Returns a copy of the link with the specified URI reference
     * either used as or converted to an absolute (non-relative)
     * URI reference. This method will overwrite any previous URI
     * reference settings but will copy the query string.</p>
     *
     * Example:<br>
     * <code>&lt;a href='$link.setAbsolute("/templates/login/index.vm")'&gt;Login Page&lt;/a&gt;</code><br>
     * produces something like<br/>
     * <code>&lt;a href="http://myserver.net/myapp/templates/login/index.vm"&gt;Login Page&lt;/a&gt;</code><br>
     * and<br>
     * <code>&lt;a href='$link.setAbsolute("http://theirserver.com/index.jsp")'&gt;Their, Inc.&lt;/a&gt;</code><br>
     * produces something like<br/>
     * <code>&lt;a href="http://theirserver.net/index.jsp"&gt;Their, Inc.&lt;/a&gt;</code><br>
     *
     * @param uri A context-relative URI reference or absolute URL.
     * @return a new instance of LinkTool with the specified URI
     * @since VelocityTools 1.3
     */
    public LinkTool setAbsolute(String uri)
    {
        // if they're creating a url for a separate site
        if (uri.startsWith("http"))
        {
            // just set the URI
            return setURI(uri);
        }
        else
        {
            // otherwise, prepend this webapp's context url
            String fullCtx = getContextURL();
            if (uri.startsWith("/"))
            {
                return copyWith(fullCtx + uri);
            }
            else
            {
                return copyWith(fullCtx + '/' + uri);
            }
        }
    }

    /**
     * Convenience method equivalent to {@link #setAbsolute}.
     * @since VelocityTools 1.3
     */
    public LinkTool absolute(String uri)
    {
        return setAbsolute(uri);
    }


    /**
     * <p>Returns a copy of the link with the given URI reference set.
     * No conversions are applied to the given URI reference. The URI
     * reference can be absolute, server-relative, relative and may
     * contain query parameters. This method will overwrite any
     * previous URI reference settings but will copy the query
     * string.</p>
     *
     * @param uri URI reference to set
     *
     * @return a new instance of LinkTool
     */
    public LinkTool setURI(String uri)
    {
        return copyWith(uri);
    }

    /**
     * Convenience method equivalent to {@link #setURI}.
     * @since VelocityTools 1.3
     */
    public LinkTool uri(String uri)
    {
        return setURI(uri);
    }

    /**
     * <p>Returns the current URI of this link as set by the setURI(String),
     * setAbsolute(String) or setRelative(String) methods. Any conversions
     * have been applied. The returned URI reference does not include query
     * data that was added with method addQueryData().</p>
     */
    public String getURI()
    {
        return uri;
    }

    /**
     * Convenience method equivalent to {@link #getURI} to enable
     * all lowercase {@code $link.uri} syntax.
     * @since VelocityTools 1.3
     */
    public String getUri()
    {
        return getURI();
    }


    /**
     * <p>Adds a key=value pair to the query data. This returns a new LinkTool
     * containing both a copy of this LinkTool's query data and the new data.
     * Query data is URL encoded before it is appended.</p>
     *
     * @param key key of new query parameter
     * @param value value of new query parameter
     *
     * @return a new instance of LinkTool
     */
    public LinkTool addQueryData(String key, Object value)
    {
        return copyWith(new QueryPair(key, value));
    }

    /**
     * <p>Adds multiple key=value pairs to the query data.
     * This returns a new LinkTool containing both a copy of
     * this LinkTool's query data and the new data.
     * Query data is URL encoded before it is appended.</p>
     *
     * @param parameters map of new query data keys to values
     * @return a new instance of LinkTool
     * @since VelocityTools 1.3
     */
    public LinkTool addQueryData(Map parameters)
    {
        // don't waste time with null/empty data
        if (parameters == null || parameters.isEmpty())
        {
            return this;
        }
        return copyWith(parameters);
    }

    /**
     * Convenience method equivalent to {@link #addQueryData}.
     * @since VelocityTools 1.3
     */
    public LinkTool param(Object key, Object value)
    {
        return addQueryData(String.valueOf(key), value);
    }

    /**
     * Convenience method equivalent to
     * {@link #addQueryData(Map parameters)}.
     * @since VelocityTools 1.3
     */
    public LinkTool params(Map parameters)
    {
        return addQueryData(parameters);
    }

    /**
     * <p>Returns this link's query data as a url-encoded string e.g.
     * <code>key=value&foo=this+is+encoded</code>.</p>
     */
    public String getQueryData()
    {
        if (queryData != null && !queryData.isEmpty())
        {

            StringBuffer out = new StringBuffer();
            for(int i=0; i < queryData.size(); i++)
            {
                out.append(queryData.get(i));
                if (i+1 < queryData.size())
                {
                    out.append(queryDataDelim);
                }
            }
            return out.toString();
        }
        return null;
    }

    /**
     * Convenience method equivalent to
     * {@link #getQueryData()}.
     * @since VelocityTools 1.3
     */
    public String getParams()
    {
        return getQueryData();
    }


    /**
     * <p>Returns the URI that addresses this web application. E.g.
     * <code>http://myserver.net/myapp</code>. This string does not end
     * with a "/".  Note! This will not represent any URI reference or
     * query data set for this LinkTool.</p>
     */
    public String getContextURL()
    {
        String scheme = request.getScheme();
        int port = request.getServerPort();

        StringBuffer out = new StringBuffer();
        out.append(request.getScheme());
        out.append("://");
        out.append(request.getServerName());
        if ((scheme.equals("http") && port != 80) ||
            (scheme.equals("https") && port != 443))
        {
            out.append(':');
            out.append(port);
        }
        out.append(request.getContextPath());
        return out.toString();
    }


    /**
     * <p>Returns the context path that addresses this web
     * application, e.g. <code>/myapp</code>. This string starts
     * with a "/" but does not end with a "/" Note! This will not
     * represent any URI reference or query data set for this
     * LinkTool.</p>
     */
    public String getContextPath()
    {
        return request.getContextPath();
    }


    /**
     * <p>Retrieves the path for the current request regardless of
     * whether this is a direct request or an include by the
     * RequestDispatcher. Note! This will not
     * represent any URI reference or query data set for this
     * LinkTool.</p>
     *
     * @since VelocityTools 1.3
     */
    public String getRequestPath()
    {
        return ServletUtils.getPath(request);
    }


    /**
     * Returns the full URI of this template without any query data.
     * e.g. <code>http://myserver.net/myapp/stuff/View.vm</code>
     * Note! The returned String will not represent any URI reference
     * or query data set for this LinkTool. A typical application of
     * this method is with the HTML base tag. For example:
     * <code>&lt;base href="$link.baseRef"&gt;</code>
     */
    public String getBaseRef()
    {
        StringBuffer out = new StringBuffer();
        out.append(getContextURL());
        out.append(getRequestPath());
        return out.toString();
    }


    /**
     * This method returns a new "self-referencing" LinkTool for the current
     * request. By default, this is merely a shortcut for calling
     * {@link #relative(String uri)} using the result of
     * {@link #getRequestPath()}.  However, this tool can be configured
     * to return an absolute URI and/or to include the parameters of the
     * current request (in addition to any others set so far).
     *
     * @see #uri(String uri)
     * @see #configure(Map params)
     * @see #setSelfAbsolute(boolean selfAbsolute)
     * @see #setSelfIncludeParameters(boolean selfParams)
     * @see #addAllParameters()
     * @since VelocityTools 1.3
     */
    public LinkTool getSelf()
    {
        // first set the uri per configuration
        LinkTool dupe;
        if (this.selfAbsolute)
        {
            dupe = uri(getBaseRef());
        }
        else
        {
            dupe = relative(getRequestPath());
        }

        // then add the params (if so configured)
        if (this.selfParams)
        {
            dupe = dupe.addAllParameters();
        }
        return dupe;
    }


    /**
     * Returns the full URI reference that's been built with this tool,
     * including the query string and anchor, e.g.
     * <code>http://myserver.net/myapp/stuff/View.vm?id=42&type=blue#foo</code>.
     * Typically, it is not necessary to call this method explicitely.
     * Velocity will call the toString() method automatically to obtain
     * a representable version of an object.
     */
    public String toString()
    {
        StringBuffer out = new StringBuffer();

        if (uri != null)
        {
            out.append(uri);
        }

        String query = getQueryData();
        if (query != null)
        {
            // Check if URI already contains query data
            if ( uri == null || uri.indexOf('?') < 0)
            {
                // no query data yet, start query data with '?'
                out.append('?');
            }
            else
            {
                // there is already query data, use data delimiter
                out.append(queryDataDelim);
            }
            out.append(query);
        }

        if (anchor != null)
        {
            out.append('#');
            out.append(encodeURL(anchor));
        }

        String str = out.toString();
        if (str.length() == 0)
        {
            // avoid a potential NPE from Tomcat's response.encodeURL impl
            return str;
        }
        else
        {
            // encode session ID into URL if sessions are used but cookies are
            // not supported
            return response.encodeURL(str);
        }
    }


    /**
     * Use the new URLEncoder.encode() method from java 1.4 if available, else
     * use the old deprecated version.  This method uses reflection to find the appropriate
     * method; if the reflection operations throw exceptions, this will return the url
     * encoded with the old URLEncoder.encode() method.
     *
     * @return String - the encoded url.
     */
    public String encodeURL(String url)
    {
        /* first try encoding with new 1.4 method */
        if (encode != null)
        {
            try
            {
                Object[] args =
                    new Object[] { url, this.response.getCharacterEncoding() };
                return (String)encode.invoke(null, args);
            }
            catch (IllegalAccessException e)
            {
                // don't keep trying if we get one of these
                encode = null;

                LOG.debug("Can't access JDK 1.4 encode method ("
                          + e + "). Using deprecated version from now on.");
            }
            catch (InvocationTargetException e)
            {
                LOG.debug("Error using JDK 1.4 encode method ("
                          + e + "). Using deprecated version.");
            }
        }
        return URLEncoder.encode(url);
    }

    /**
     * Instructs this LinkTool to ignore the specified parameter when
     * copying the current request's parameters.
     *
     * @param parameterName The name of the parameter to ignore.
     *
     * @see #addAllParameters()
     */
    public LinkTool addIgnore(String parameterName)
    {
        return copyWithIgnore(parameterName);
    }

    /**
     * Adds all of the current request's parameters to this link's
     * "query data". Any parameters that have been set to be ignored
     * will be ignored.
     *
     * @return A LinkTool object with all of the current request's parameters
     *         added to it.
     * @see #addIgnore(String)
     */
    public LinkTool addAllParameters()
    {
        if (this.parametersToIgnore != null)
        {
            // Since we're adding all these parameters at once, there's no
            // reason to make a copy of each LinkTool along the way, as might
            // be done if we wrapped a call to addQueryData in a loop over
            // all request parameters.
            //
            // Instead, we copy the current parameters, filter out those we
            // want to ignore, and then use the copyWith(Map) method to
            // copy all the parameters into this LinkTool.
            Map params = new HashMap(request.getParameterMap());
            for (Iterator i = parametersToIgnore.iterator(); i.hasNext(); )
            {
                params.remove(i.next());
            }
            return copyWith(params);
        }
        else
        {
            return copyWith(request.getParameterMap());
        }
    }


    // --------------------------------------------- Internal Class -----------

    /**
     * Internal util class to handle representation and
     * encoding of key/value pairs in the query string
     */
    protected final class QueryPair
    {

        private final String key;
        private final Object value;

        /**
         * Construct a new query pair.
         *
         * @param key query pair
         * @param value query value
         */
        public QueryPair(String key, Object value)
        {
            this.key = key;
            this.value = value;
        }

        /**
         * Gets the key for this QueryPair.
         */
        public String getKey()
        {
            return key;
        }

        /**
         * Return the URL-encoded query string.
         */
        public String toString()
        {
            StringBuffer out = new StringBuffer();
            if (value == null)
            {
                out.append(encodeURL(key));
                out.append('=');
                /* Interpret null as "no value" */
            }
            else if (value instanceof List)
            {
                appendAsArray(out, key, ((List)value).toArray());
            }
            else if (value instanceof Object[])
            {
                appendAsArray(out, key, (Object[])value);
            }
            else
            {
                out.append(encodeURL(key));
                out.append('=');
                out.append(encodeURL(String.valueOf(value)));
            }
            return out.toString();
        }

        /* Utility method to avoid logic duplication in toString() */
        private void appendAsArray(StringBuffer out, String key, Object[] arr)
        {
            String encKey = encodeURL(key);
            for (int i=0; i < arr.length; i++)
            {
                out.append(encKey);
                out.append('=');
                if (arr[i] != null)
                {
                    out.append(encodeURL(String.valueOf(arr[i])));
                }
                if (i+1 < arr.length)
                {
                    out.append(queryDataDelim);
                }
            }
        }

    }


}
