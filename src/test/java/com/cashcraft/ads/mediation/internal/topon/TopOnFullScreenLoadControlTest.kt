package com.cashcraft.ads.mediation.internal.topon

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.internal.AdLoadClock
import com.cashcraft.ads.mediation.internal.AdLoadSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TopOnFullScreenLoadControlTest {
    @Test fun `每轮最多四次实际请求且退避分别为二点一四八秒`() {
        val h = Harness()
        h.request()
        h.fail()
        h.advanceTo(2_099)
        assertEquals(listOf(0L), h.requestTimes())
        h.advanceTo(2_100)
        h.fail()
        h.advanceTo(6_099)
        assertEquals(listOf(0L, 2_100L), h.requestTimes())
        h.advanceTo(6_100)
        h.fail()
        h.advanceTo(14_099)
        assertEquals(listOf(0L, 2_100L, 6_100L), h.requestTimes())
        h.advanceTo(14_100)
        h.fail()
        h.advanceTo(100_000)
        assertEquals(listOf(0L, 2_100L, 6_100L, 14_100L), h.requestTimes())
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun `等待中重复机会不提前不重复任务也不刷新预算或截止`() {
        val h = Harness()
        h.request()
        h.fail()
        val pending = h.tasks.single()
        h.advanceTo(1_000)
        repeat(10) { h.request(newOpportunity = true) }
        assertSame(pending, h.tasks.single())
        assertEquals(1, h.postCount)
        h.advanceTo(2_100)
        h.fail()
        assertEquals(6_100L, h.tasks.single().at)
        h.advanceTo(6_100)
        h.fail()
        h.advanceTo(14_100)
        h.fail()
        assertEquals(4, h.requests.size)
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun `不同广告位分别计预算和冷却`() {
        val h = Harness()
        h.request("open")
        h.fail("open")
        h.advanceTo(1_000)
        h.request("reward")
        h.fail("reward")
        assertEquals(listOf(2_100L, 3_100L), h.tasks.map { it.at }.sorted())
        h.advanceTo(2_100)
        assertEquals(listOf(0L, 2_100L), h.requestTimes("open"))
        assertEquals(listOf(1_000L), h.requestTimes("reward"))
        h.advanceTo(3_100)
        assertEquals(listOf(1_000L, 3_100L), h.requestTimes("reward"))
    }

    @Test fun `外部共享冷却在执行时复查并取较晚截止`() {
        val h = Harness()
        h.request()
        h.fail()
        h.advanceTo(1_000)
        h.cooldown.failed("open")
        h.advanceTo(2_100)
        assertEquals(listOf(0L), h.requestTimes())
        assertEquals(3_100L, h.tasks.single().at)
        h.advanceTo(3_100)
        h.fail()
        assertEquals(7_100L, h.tasks.single().at)
    }

    @Test fun `耗尽后普通策略通知及前台恢复不重开而新机会可以`() {
        val h = Harness()
        h.exhaust()
        repeat(3) {
            h.control.onPolicyChanged(true)
            h.request()
            h.control.pause()
            h.control.resume()
        }
        assertEquals(4, h.requests.size)
        assertTrue(h.tasks.isEmpty())
        h.request(newOpportunity = true)
        assertEquals(16_200L, h.tasks.single().at)
        h.advanceTo(16_200)
        h.fail()
        assertEquals(18_300L, h.tasks.single().at)
    }

    @Test fun `禁止恢复允许可重开耗尽但重复允许通知不能重开`() {
        val h = Harness()
        h.exhaust()
        h.policy(true)
        assertTrue(h.tasks.isEmpty())
        h.policy(false)
        h.policy(true)
        assertEquals(16_200L, h.tasks.single().at)
        h.advanceTo(16_200)
        assertEquals(5, h.requests.size)
    }

    @Test fun `连续重试保留失败预算和绝对截止`() {
        val h = Harness()
        h.request()
        h.fail()
        h.advanceTo(1_000)
        h.advanceTo(1_500)
        assertEquals(2_100L, h.tasks.single().at)
        h.advanceTo(2_100)
        h.fail()
        h.advanceTo(3_000)
        h.advanceTo(7_000)
        assertEquals(listOf(0L, 2_100L, 6_100L), h.requestTimes())
        h.fail()
        assertEquals(15_000L, h.tasks.single().at)
        h.advanceTo(15_000)
        h.fail()
        assertEquals(4, h.requests.size)
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun `活跃轮次的许可撤回恢复不刷新次数且到期后立即继续`() {
        val h = Harness()
        h.request()
        h.fail()
        h.advanceTo(1_000)
        h.policy(false)
        h.request(newOpportunity = true)
        h.advanceTo(3_000)
        assertEquals(1, h.requests.size)
        h.policy(true)
        h.fail()
        assertEquals(7_000L, h.tasks.single().at)
    }

    @Test fun `许可变化通知前也会在执行时阻止请求`() {
        val h = Harness()
        h.request()
        h.fail()
        h.allowed = false
        h.advanceTo(2_100)
        assertEquals(1, h.requests.size)
        assertTrue(h.tasks.isEmpty())
        h.policy(false)
        h.policy(true)
        assertEquals(listOf(0L, 2_100L), h.requestTimes())
        h.fail()
        assertEquals(6_100L, h.tasks.single().at)
    }

    @Test fun `实际调用前复查许可且不消耗失败预算`() {
        val h = Harness()
        h.onReadyCheck = { h.allowed = false }
        h.request()
        assertTrue(h.requests.isEmpty())
        h.onReadyCheck = null
        h.policy(false)
        h.policy(true)
        h.fail()
        assertEquals(2_100L, h.tasks.single().at)
    }

    @Test fun `首次加载和失败重试按截止时间执行`() {
        val h = Harness()
        h.request()
        h.control.onPolicyChanged(true)
        assertEquals(listOf(0L), h.requestTimes())
        assertTrue(h.tasks.isEmpty())
        h.fail()
        h.advanceTo(2_100)
        assertEquals(listOf(0L, 2_100L), h.requestTimes())
        h.fail()
        assertEquals(6_100L, h.tasks.single().at)
    }

    @Test fun `执行时已有库存或正在加载均不发伪请求`() {
        val ready = Harness()
        ready.request()
        ready.fail()
        ready.slot().ready = true
        ready.advanceTo(2_100)
        assertEquals(1, ready.requests.size)
        assertTrue(ready.tasks.isEmpty())

        val loading = Harness()
        loading.request()
        loading.fail()
        loading.slot().loading = true
        loading.advanceTo(2_100)
        assertEquals(1, loading.requests.size)
        assertTrue(loading.tasks.isEmpty())
        loading.slot().loading = false
        loading.control.resume()
        assertEquals(2, loading.requests.size)
    }

    @Test fun `afterShow补货绕过旧库存且重试继续保留补货语义`() {
        val h = Harness()
        h.slot().ready = true
        h.request()
        assertTrue(h.requests.isEmpty())
        h.request(afterShow = true)
        h.fail()
        h.request()
        h.advanceTo(2_100)
        assertEquals(listOf(true, true), h.requests.map { it.refill })
    }

    @Test fun `退避期间afterShow不提前请求而耗尽后的补货可重开`() {
        val h = Harness()
        h.request()
        h.fail()
        val pending = h.tasks.single()
        h.slot().ready = true
        h.request(afterShow = true)
        assertSame(pending, h.tasks.single())
        h.advanceTo(2_100)
        assertEquals(listOf(false, true), h.requests.map { it.refill })
        h.fail()
        h.advanceTo(6_100)
        h.fail()
        h.advanceTo(14_100)
        h.fail()
        h.request(afterShow = true)
        h.advanceTo(16_200)
        assertEquals(5, h.requests.size)
        assertTrue(h.requests.last().refill)
    }

    @Test fun `成功结束轮次并清除失败次数`() {
        val h = Harness()
        h.request()
        h.fail()
        h.advanceTo(2_100)
        h.slot().loading = false
        h.control.loaded("open")
        h.request(newOpportunity = true)
        h.fail()
        assertEquals(4_200L, h.tasks.single().at)
    }

    @Test fun `失败通知同步重入时冷却和预算已经更新`() {
        val h = Harness()
        h.onInvoke = {
            h.fail()
            assertEquals(2_100L, h.cooldown.remainingMillis("open"))
            if (h.requests.size < 4) h.request(newOpportunity = true)
        }
        h.request()
        h.advanceTo(14_100)
        assertEquals(listOf(0L, 2_100L, 6_100L, 14_100L), h.requestTimes())
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun `被取消的旧任务即使收到回调也不能清除或执行新任务`() {
        val h = Harness()
        h.request()
        h.fail()
        val old = h.tasks.single().task
        h.control.pause()
        h.control.resume()
        val replacement = h.tasks.single()
        old.run()
        assertSame(replacement, h.tasks.single())
        assertEquals(1, h.requests.size)
        h.advanceTo(2_100)
        assertEquals(2, h.requests.size)
    }

    @Test fun `过早唤醒仍等待原截止不发请求`() {
        val h = Harness()
        h.request()
        h.fail()
        val early = h.tasks.removeAt(0)
        h.advanceTo(500)
        early.task.run()
        assertEquals(1, h.requests.size)
        assertEquals(2_100L, h.tasks.single().at)
        h.advanceTo(2_100)
        assertEquals(2, h.requests.size)
    }

    @Test fun `调用前取消不会产生请求事件或失败并保留初次预算`() {
        val h = Harness()
        h.acceptInvocation = false
        h.request()
        assertTrue(h.requests.isEmpty())
        assertTrue(h.tasks.isEmpty())
        h.acceptInvocation = true
        h.request()
        h.fail()
        assertEquals(2_100L, h.tasks.single().at)
    }

    @Test fun `重复或迟到失败不刷新冷却不耗预算也不接受成功`() {
        val h = Harness()
        assertFalse(h.control.failed("unknown"))
        assertEquals(0L, h.cooldown.remainingMillis("unknown"))
        h.request()
        assertTrue(h.fail())
        val pending = h.tasks.single()
        h.advanceTo(1_000)
        assertFalse(h.control.failed("open"))
        assertFalse(h.control.loaded("open"))
        assertEquals(1_100L, h.cooldown.remainingMillis("open"))
        assertSame(pending, h.tasks.single())
        h.advanceTo(2_100)
        h.slot().loading = false
        assertTrue(h.control.loaded("open"))
        assertFalse(h.control.loaded("open"))
        h.advanceTo(2_200)
        assertFalse(h.control.failed("open"))
        assertEquals(0L, h.cooldown.remainingMillis("open"))
        h.request(newOpportunity = true)
        assertEquals(listOf(0L, 2_100L, 2_200L), h.requestTimes())
        h.fail()
        assertEquals(4_300L, h.tasks.single().at)
    }

    @Test fun `同步失败回调后再抛异常不能重复接受失败`() {
        val h = Harness()
        val error = IllegalStateException("同步加载异常")
        h.onInvoke = {
            assertTrue(h.fail())
            throw error
        }
        assertSame(error, runCatching { h.request() }.exceptionOrNull())
        val pending = h.tasks.single()
        h.advanceTo(500)
        assertFalse(h.control.failed("open"))
        assertSame(pending, h.tasks.single())
        assertEquals(1_600L, h.cooldown.remainingMillis("open"))
        h.onInvoke = null
        h.advanceTo(2_100)
        h.fail()
        assertEquals(6_100L, h.tasks.single().at)
    }

    @Test fun `同步成功回调后再抛异常不能改成失败`() {
        val h = Harness()
        val error = IllegalStateException("同步加载异常")
        h.onInvoke = {
            h.slot().loading = false
            assertTrue(h.control.loaded("open"))
            throw error
        }
        assertSame(error, runCatching { h.request() }.exceptionOrNull())
        assertFalse(h.control.failed("open"))
        assertEquals(0L, h.cooldown.remainingMillis("open"))
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun `策略恢复和前台恢复的同步加载回调可重入新增广告位`() {
        for (fromPolicy in listOf(true, false)) {
            val h = Harness()
            if (fromPolicy) h.policy(false) else h.control.pause()
            h.request("open")
            h.request("reward")
            h.onInvoke = {
                if (h.requests.last().id == "open") h.request("inter")
            }
            if (fromPolicy) h.policy(true) else h.control.resume()
            assertEquals(listOf("open", "inter", "reward"), h.requests.map { it.id })
            assertTrue(h.tasks.isEmpty())
        }
    }

    @Test fun `二零零八逐次失败仍发送真实请求终态而等待不发额外事件`() {
        val h = Harness()
        val events = mutableListOf<AdEvent>()
        h.onInvoke = {
            val requestId = "request-${h.requests.size}"
            val session = AdLoadSession(
                AdEventListener(events::add), AdPlatform.TOPON, AdMediationMode.TOPON,
                AdFormat.APP_OPEN, "preload", "open", requestId, requestId, 1, 1, h.now,
                AdLoadClock { h.now }, deferUntilRequest = true,
            )
            session.invokeLoad {
                assertTrue(h.fail())
                session.failed("error", "2008", "加载失败", null)
            }
        }
        h.request()
        repeat(5) { h.request(newOpportunity = true) }
        h.advanceTo(2_099)
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map { it.name })
        h.advanceTo(14_100)
        assertEquals(4, events.count { it.name == AdEventName.LOAD })
        assertEquals(4, events.count { it.name == AdEventName.LOAD_FAIL })
        val failures = events.filter { it.name == AdEventName.LOAD_FAIL }
        assertTrue(failures.all { it.errorCode == "2008" && it.reason == "ad_error" })
        assertEquals(4, failures.map { it.requestId }.distinct().size)
        h.advanceTo(100_000)
        assertEquals(8, events.size)
    }

    private data class Slot(var ready: Boolean = false, var loading: Boolean = false)
    private data class Request(val id: String, val at: Long, val refill: Boolean)
    private data class Task(val task: Runnable, val at: Long)

    private class Harness {
        var now = 0L
        var allowed = true
        var acceptInvocation = true
        var postCount = 0
        var onReadyCheck: (() -> Unit)? = null
        var onInvoke: (() -> Unit)? = null
        val cooldown = TopOnLoadCooldown { now }
        val tasks = mutableListOf<Task>()
        val requests = mutableListOf<Request>()
        private val slots = mutableMapOf<String, Slot>()
        val control = TopOnFullScreenLoadControl(
            clock = { now },
            cooldown = cooldown,
            postDelayed = { task, delay ->
                postCount++
                tasks += Task(task, now + delay)
            },
            cancel = { task -> tasks.removeAll { it.task === task } },
            canLoad = { allowed },
        )

        fun slot(id: String = "open") = slots.getOrPut(id) { Slot() }

        fun request(id: String = "open", newOpportunity: Boolean = false, afterShow: Boolean = false) {
            val slot = slot(id)
            control.request(id, newOpportunity, afterShow,
                isReady = { onReadyCheck?.invoke(); slot.ready },
                isLoading = { slot.loading },
                load = { refill ->
                    if (!acceptInvocation) false else {
                        slot.loading = true
                        requests += Request(id, now, refill)
                        onInvoke?.invoke()
                        true
                    }
                },
            )
        }

        fun fail(id: String = "open"): Boolean {
            slot(id).loading = false
            return control.failed(id)
        }

        fun policy(allowed: Boolean) {
            this.allowed = allowed
            control.onPolicyChanged(allowed)
        }

        fun advanceTo(target: Long) {
            check(target >= now)
            while (true) {
                val next = tasks.filter { it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                next.task.run()
            }
            now = target
        }

        fun requestTimes(id: String = "open") = requests.filter { it.id == id }.map { it.at }

        fun exhaust() {
            request()
            fail()
            advanceTo(2_100)
            fail()
            advanceTo(6_100)
            fail()
            advanceTo(14_100)
            fail()
        }
    }
}
