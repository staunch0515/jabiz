package com.jabiz.quizbuks.help;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

/** The handler of {@value HelpMaterialsJob#TYPE}: the snapshot's text as the job's file, checked against its hash. */
@Component
class HelpMaterialsHandler implements ExternalJobHandler<HelpMaterialsJob.Input, HelpMaterialsJob.Result> {

    @Override
    public ExternalJobResult<HelpMaterialsJob.Result> run(HelpMaterialsJob.Input input, ExternalJobContext ctx) {
        if (input.text() == null || !HelpMaterialsText.hash(input.text()).equals(input.contentHash())) {
            throw ExternalJobException.permanent(HelpCodes.MATERIALS_CHANGED, "The snapshot's text is not the one its hash names");
        }
        byte[] bytes = HelpMaterialsText.bytes(input.text());
        return ExternalJobResult.of(new HelpMaterialsJob.Result(input.entry(), input.language(), input.contentHash(),
                input.builtAt(), bytes.length))
            .file(HelpMaterialsJob.FILE, "help-materials-" + input.entry() + "-" + input.language() + ".txt", bytes);
    }
}
