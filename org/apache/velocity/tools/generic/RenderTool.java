package org.apache.velocity.tools.generic;

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

import java.io.StringWriter;
import java.util.Map;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;

/**
 * This tool exposes methods to evaluate the given
 * strings as VTL (Velocity Template Language)
 * using the given context.
 * <p>
 *   NOTE: These examples assume you have placed an
 *   instance of the current context within itself
 *   as 'ctx'. And, of course, the RenderTool is
 *   assumed to be available as 'render'.
 * </p>
 * <pre>
 * Example of eval():
 *      Input
 *      -----
 *      #set( $list = [1,2,3] )
 *      #set( $object = '$list' )
 *      #set( $method = 'size()' )
 *      $render.eval($ctx, "${object}.$method")
 *
 *      Output
 *      ------
 *      3
 *
 * Example of recurse():
 *      Input
 *      -----
 *      #macro( say_hi )hello world!#end
 *      #set( $foo = '#say_hi()' )
 *      #set( $bar = '$foo' )
 *      $render.recurse($ctx, $bar)
 *
 *      Output
 *      ------
 *      hello world!
 *
 *
 * Toolbox configuration:
 * &lt;tool&gt;
 *   &lt;key&gt;render&lt;/key&gt;
 *   &lt;class&gt;org.apache.velocity.tools.generic.RenderTool&lt;/class&gt;
 * &lt;/tool&gt;
 * </pre>
 *
 * <p>Ok, so these examples are really lame.  But, it seems like
 * someone out there is always asking how to do stuff like this
 * and we always tell them to write a tool.  Now we can just tell
 * them to use this tool.</p>
 *
 * <p>This tool is safe (and optimized) for use in the application
 * scope of a servlet environment.</p>
 *
 * @author Nathan Bubna
 * @version $Revision$ $Date$
 */
public class RenderTool
{
    /**
     * The maximum number of loops allowed when recursing.
     * @since VelocityTools 1.2
     */
    public static final int DEFAULT_PARSE_DEPTH = 20;
    public static final String KEY_PARSE_DEPTH = "parse.depth";
    public static final String KEY_CATCH_EXCEPTIONS = "catch.exceptions";

    private static final String LOG_TAG = "RenderTool.eval()";

    private VelocityEngine engine = null;
    private int parseDepth = DEFAULT_PARSE_DEPTH;
    private boolean catchExceptions = true;

    /**
     * Looks for parse depth and catch.exceptions parameters.
     * @since VelocityTools 1.3
     */
    public void configure(Map params)
    {
        ValueParser parser = new ValueParser(params);
        int depth = parser.getInt(KEY_PARSE_DEPTH, DEFAULT_PARSE_DEPTH);
        setParseDepth(depth);

        boolean catchEm = parser.getBoolean(KEY_CATCH_EXCEPTIONS, true);
        setCatchExceptions(catchEm);
    }

    /**
     * Allow user to specify a VelocityEngine to be used
     * in place of the Velocity singleton.
     */
    public void setVelocityEngine(VelocityEngine ve)
    {
        this.engine = ve;
    }

    /**
     * Set the maximum number of loops allowed when recursing.
     *
     * @since VelocityTools 1.2
     */
    public void setParseDepth(int depth)
    {
        this.parseDepth = depth;
    }

    /**
     * Get the maximum number of loops allowed when recursing.
     *
     * @since VelocityTools 1.2
     */
    public int getParseDepth()
    {
        return this.parseDepth;
    }

    /**
     * Sets whether or not the render() and eval() methods should catch
     * exceptions during their execution or not.
     * @since VelocityTools 1.3
     */
    public void setCatchExceptions(boolean catchExceptions)
    {
        this.catchExceptions = catchExceptions;
    }

    /**
     * Returns <code>true</code> if this render() and eval() methods will
     * catch exceptions thrown during rendering.
     * @since VelocityTools 1.3
     */
    public boolean getCatchExceptions()
    {
        return this.catchExceptions;
    }

    /**
     * <p>Evaluates a String containing VTL using the current context,
     * and returns the result as a String.  By default if this fails, then
     * <code>null</code> will be returned, though this tool can be configured
     * to let Exceptions pass through. This evaluation is not recursive.</p>
     *
     * @param ctx the current Context
     * @param vtl the code to be evaluated
     * @return the evaluated code as a String
     */
    public String eval(Context ctx, String vtl) throws Exception
    {
        if (this.catchExceptions)
        {
            try
            {
                return internalEval(ctx, vtl);
            }
            catch (Exception e)
            {
                String msg = LOG_TAG + " threw Exception: " + e;
                if (engine == null)
                {
                    Velocity.debug(msg);
                }
                else
                {
                    engine.debug(msg);
                }
                return null;
            }
        }
        else
        {
            return internalEval(ctx, vtl);
        }
    }


    /* Internal implementation of the eval() method function. */
    private String internalEval(Context ctx, String vtl) throws Exception
    {
        if (vtl == null)
        {
            return null;
        }
        StringWriter sw = new StringWriter();
        boolean success;
        if (engine == null)
        {
            success = Velocity.evaluate(ctx, sw, LOG_TAG, vtl);
        }
        else
        {
            success = engine.evaluate(ctx, sw, LOG_TAG, vtl);
        }
        if (success)
        {
            return sw.toString();
        }
        /* or would it be preferable to return the original? */
        return null;
    }

    /**
     * <p>Recursively evaluates a String containing VTL using the
     * current context, and returns the result as a String. It
     * will continue to re-evaluate the output of the last
     * evaluation until an evaluation returns the same code
     * that was fed into it or the number of recursive loops
     * exceeds the set parse depth.</p>
     *
     * @param ctx the current Context
     * @param vtl the code to be evaluated
     * @return the evaluated code as a String
     */
    public String recurse(Context ctx, String vtl) throws Exception
    {
        return internalRecurse(ctx, vtl, 0);
    }

    protected String internalRecurse(Context ctx, String vtl, int count) throws Exception
    {
        String result = eval(ctx, vtl);
        if (result == null || result.equals(vtl))
        {
            return result;
        }
        else
        {
            // if we haven't reached our parse depth...
            if (count < parseDepth)
            {
                // continue recursing
                return internalRecurse(ctx, result, count + 1);
            }
            else
            {
                // abort and return what we have so far
                //FIXME: notify the developer or user somehow??
                return result;
            }
        }
    }

}
