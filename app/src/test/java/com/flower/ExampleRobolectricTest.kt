package com.flower

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.flower.network.DiscoveredHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Screen Share", appName)
  }

  @Test
  fun `verify discovered host model formatting`() {
    val host = DiscoveredHost(
      id = "192.168.1.50:8080",
      name = "Pixel 8",
      ip = "192.168.1.50",
      port = 8080,
      width = 1080,
      height = 2400
    )
    assertEquals("http://192.168.1.50:8080/stream", host.streamUrl)
    assertEquals("http://192.168.1.50:8080", host.infoUrl)
    assertNotNull(host.lastSeenMs)
  }
}

