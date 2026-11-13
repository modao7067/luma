package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OpenCodeQuestionActionsTest {
    private data class Request(val method:String,val path:String,val body:String)
    private class Harness(private val drop:Boolean=false):AutoCloseable {
        val calls=CopyOnWriteArrayList<Request>()
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        @Volatile var closed=false
        val worker=Thread {
            while(!closed) try { server.accept().use { socket ->
                socket.soTimeout=5000
                val input=socket.getInputStream().bufferedReader();val first=input.readLine() ?: return@use
                var length=0
                while(true){val line=input.readLine();if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                val body=CharArray(length);var at=0;while(at<length){val n=input.read(body,at,length-at);check(n>0);at+=n}
                calls+=Request(first.substringBefore(' '),first.split(' ')[1],String(body))
                if(!drop){socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray());socket.getOutputStream().flush()}
            }} catch(e:Exception){if(!closed)throw e}
        }.apply{isDaemon=true;start()}
        val app:Application=RuntimeEnvironment.getApplication()
        val record=SessionRecord("local","fixture","noroot","/workspace",threadId="native",cli="OpenCode")
        val session=OpenCodeSession(record,AgentRuntime(app),{}, {""},{_,_->},{})
        val form=obj("id" to "f","fields" to JSONArray().put(obj("key" to "q","title" to "Question","type" to "string","required" to true)))
        var view=SessionView(SessionSummary("local","fixture","noroot","/workspace",cli="OpenCode"),connected=true,requests=listOf(OpenCodeProjection.form(form)))
        init {
            set(session,"client",OpenCodeClient("http://127.0.0.1:${server.localPort}","synthetic-loopback-only"))
            set(session,"view",view);(field(session,"forms") as MutableMap<String,JSONObject>)["f"]=form
        }
        fun await(done:()->Boolean){val end=System.nanoTime()+3_000_000_000;while(!done()&&System.nanoTime()<end){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(30))};assertTrue(done())}
        fun current()=field(session,"view") as SessionView
        override fun close(){session.close();closed=true;server.close();worker.join(1500)}
    }
    @Test fun skipUsesNativeDeleteWithoutSendingEvenSuppliedDraftAndCannotDoubleAnswer()=Harness().use{h->
        assertEquals("skip",h.view.requests.single().skipChoice)
        h.session.answer("form:f","skip",mapOf("q" to listOf("must never be sent")))
        h.session.answer("form:f","skip",emptyMap());h.await{h.calls.isNotEmpty()}
        assertEquals(Request("DELETE","/api/session/native/form/f",""),h.calls.single())
    }
    @Test fun submitRetainsOriginalFormReplyValuesAndPermissionRejectionKeepsItsOriginalEndpoint()=Harness().use{h->
        h.session.answer("form:f","submit",mapOf("q" to listOf("original answer")));h.await{h.calls.isNotEmpty()}
        val request=h.calls.single();assertEquals("POST",request.method);assertEquals("/api/session/native/form/f/reply",request.path)
        assertEquals("original answer",JSONObject(request.body).getJSONObject("answer").getString("q"))
        set(h.session,"view",h.current().copy(requests=listOf(UserRequest("permission:p","Permission",""))))
        h.session.answer("permission:p","reject",emptyMap());h.await{h.calls.size==2}
        assertEquals("/api/session/native/permission/p/reply",h.calls.last().path);assertEquals("reject",JSONObject(h.calls.last().body).getString("decision"))
    }
    @Test fun lostNativeCancelAckRemainsUncertainAndNeverResendsOrClaimsSuccess()=Harness(drop=true).use{h->
        h.session.answer("form:f","skip",emptyMap());h.await{h.current().error!=null}
        assertTrue(h.current().requests.single().answering)
        h.session.answer("form:f","skip",emptyMap());assertEquals(1,h.calls.size)
    }
    companion object{
        private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        private fun set(o:Any,n:String,v:Any){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
    }
}
