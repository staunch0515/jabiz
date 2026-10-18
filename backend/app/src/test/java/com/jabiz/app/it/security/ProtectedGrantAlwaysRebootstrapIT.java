package com.jabiz.app.it.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review L2 verification (decision D48): with {@code jabiz.security.protected-grants.always=true}, when every user and
 * the role {@code ADMIN} are gone the platform gives {@code *} to nobody unapproved again; startup says so plainly
 * instead of failing. (Nobody signs in here: a user who did cannot be deleted.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.protected-grants.always=true",
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1",
    "it.always-rebootstrap=true"})
@ExtendWith(OutputCaptureExtension.class)
class ProtectedGrantAlwaysRebootstrapIT extends SecurityItSupport {

    @Autowired
    @Qualifier("bootstrapAdministrator")
    ApplicationRunner bootstrap;

    @Test
    void whenEveryUserIsGoneTheFirstAdministratorIsNotMadeAgain(CapturedOutput output) throws Exception {
        ProtectedGrantRebootstrapIT.deleteEveryUserAndTheAdministratorRole(this);
        bootstrap.run(null);
        assertThat(output.getOut()).contains("Did not create the administrator root again")
            .contains("jabiz.security.protected-grants");
        assertThat(query("SELECT 1 FROM sec_user_version u JOIN op_process p ON p.process_seq_id = u.process_seq_id"
            + " WHERE p.process_name = 'SEC_BOOTSTRAP_ADMIN' AND u.version_no = 1")).hasSize(1);
    }
}
