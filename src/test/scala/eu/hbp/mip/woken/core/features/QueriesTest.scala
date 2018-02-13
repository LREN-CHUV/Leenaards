/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core.features

import org.scalatest.{ Matchers, WordSpec }

class QueriesTest extends WordSpec with Matchers {

  "SqlStrings" should {
    import Queries.SqlStrings

    "produce safe numerical output" in {
      "1".safeValue shouldBe "1"
      "-2".safeValue shouldBe "-2"
      "+3".safeValue shouldBe "3"
      "1.23".safeValue shouldBe "1.23"
      "-1.23".safeValue shouldBe "-1.23"
    }

    "wrap strings into quotes" in {
      "a".safeValue shouldBe "'a'"
      "3e".safeValue shouldBe "'3e'"
      "a'b'c".safeValue shouldBe "'a''b''c'"
    }

    "prevent SQL injection from values [security]" in {
      "Bobby'; DROP DATABASE; --".safeValue shouldBe "'Bobby''; DROP DATABASE; --'"
      "10; DROP DATABASE; --".safeValue shouldBe "'10; DROP DATABASE; --'"
      "' + (SELECT TOP 1 password FROM users ) + '".safeValue shouldBe "''' + (SELECT TOP 1 password FROM users ) + '''"
    }

    "quote identifiers" in {
      "a".identifier shouldBe """"a""""
      "3".identifier shouldBe """"3""""
      "a c".identifier shouldBe """"a c""""
    }

    "prevent SQL injection from identifiers [security]" in {
      """Bob"; DROP DATABASE --""".identifier shouldBe """"Bob""; DROP DATABASE --""""
    }
  }

  "FilterRuleToSql" should {
    // TODO
  }

}
