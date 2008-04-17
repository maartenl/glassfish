/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the license at
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package com.sun.enterprise.v3.services.impl;

import com.sun.grizzly.Context;
import com.sun.grizzly.ProtocolFilter;
import com.sun.grizzly.http.HtmlHelper;
import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.tcp.StaticResourcesAdapter;
import com.sun.grizzly.util.ByteBufferInputStream;
import com.sun.grizzly.util.OutputWriter;
import com.sun.grizzly.util.WorkerThread;
import com.sun.grizzly.util.buf.ByteChunk;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.util.logging.Level;

/**
 * Specialized ProtocolFilter that properly configure the Http Adapter on the fly.
 * 
 * @author Jeanfrancois Arcand
 */
public class HttpProtocolFilter implements ProtocolFilter {

    
    private GrizzlyEmbeddedHttp grizzlyEmbeddedHttp;
    
    
    /**
     * The Grizzly's wrapped ProtocolFilter.
     */
    private final ProtocolFilter wrappedFilter;
    
    /**
     *  Fallback context-root information
     */
    private ContextRootMapper.ContextRootInfo fallbackContextRootInfo;
    
    
    private static byte[] errorBody =
            HttpUtils.getErrorPage("Glassfish/v3","HTTP Status 404");
      
    
    public HttpProtocolFilter(ProtocolFilter wrappedFilter, GrizzlyEmbeddedHttp grizzlyEmbeddedHttp) {
        this.grizzlyEmbeddedHttp = grizzlyEmbeddedHttp;
        this.wrappedFilter = wrappedFilter;
        
        StaticResourcesAdapter adapter = new StaticResourcesAdapter(){
            @Override
              protected void customizedErrorPage(Request req,
                    Response res) throws Exception {
                
                ByteChunk chunk = new ByteChunk();
                chunk.setBytes(errorBody,0,errorBody.length);
                res.setContentLength(errorBody.length);        
                res.sendHeaders();
                res.doWrite(chunk);
            }              
        };
        adapter.setRootFolder(GrizzlyEmbeddedHttp.getWebAppRootPath());
                        
        fallbackContextRootInfo = new ContextRootMapper.ContextRootInfo(adapter,
                null, null);
        
    }

    
    public boolean execute(Context ctx) throws IOException {
        WorkerThread thread = (WorkerThread)Thread.currentThread();
        ByteBuffer byteBuffer = thread.getByteBuffer();

        try {
            // Make sure we have enough bytes to parse context-root
            if (byteBuffer.position() < ContextRootMapper.MIN_CONTEXT_ROOT_READ_BYTES) {
                if (GrizzlyUtils.readToWorkerThreadBuffers(ctx.getSelectionKey(), 
                        ByteBufferInputStream.getDefaultReadTimeout()) == -1) {
                    ctx.setKeyRegistrationState(
                        Context.KeyRegistrationState.CANCEL);
                    return false;
                }
            }

            boolean wasMap = grizzlyEmbeddedHttp.getContextRootMapper().map(
                    (GlassfishProtocolChain) ctx.getProtocolChain(),
                    byteBuffer, null,
                    fallbackContextRootInfo);
            if (!wasMap) {
                //TODO: Some Application might not have Adapter. Might want to
                //add a dummy one instead of sending a 404.
                try {
                    ByteBuffer bb = HtmlHelper.getErrorPage("Not Found", "HTTP/1.1 404 Not Found\n");
                    OutputWriter.flushChannel
                            (ctx.getSelectionKey().channel(),bb);
                } catch (IOException ex){
                    GrizzlyEmbeddedHttp.logger().log(Level.FINE, "Send Error failed", ex);
                } finally {
                    thread.getByteBuffer().clear();
                }
                return false;               
            }
        } catch (IOException ex) {
            GrizzlyEmbeddedHttp.logger().fine(ex.getMessage());
        }

        return wrappedFilter.execute(ctx);
    }

    
    /**
     * Execute the wrapped ProtocolFilter.
     */
    public boolean postExecute(Context ctx) throws IOException {
        return wrappedFilter.postExecute(ctx);
    }    
    
    public void setFallbackAdapter(Adapter adapter) {
        fallbackContextRootInfo.setAdapter(adapter);
    }
    
    public Adapter getFallbackAdapter() {
        return fallbackContextRootInfo.getAdapter();
    }    
}
