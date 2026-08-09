package com.example

import com.example.util.IranianBankHelper
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun bankBinPrefixes_resolveCorrectly() {
    val saderatBankCard = IranianBankHelper.getBankByCardNumber("6037690000000000")
    assertEquals("بانک صادرات ایران", saderatBankCard.name)

    val saderatBankCard2 = IranianBankHelper.getBankByCardNumber("9037690000000000")
    assertEquals("بانک صادرات ایران", saderatBankCard2.name)

    val melliBankCard = IranianBankHelper.getBankByCardNumber("6037990000000000")
    assertEquals("بانک ملی ایران", melliBankCard.name)

    val melliBankCard2 = IranianBankHelper.getBankByCardNumber("1700190000000000")
    assertEquals("بانک ملی ایران", melliBankCard2.name)
  }
}

