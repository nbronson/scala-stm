%string metauml_defaultFont;
%metauml_defaultFont := "ul9r8r";
%string metauml_defaultFontOblique;
%metauml_defaultFontOblique := "ul9ro8r";
%string metauml_defaultFontBold;
%metauml_defaultFontBold := "ul9b8r";
%string metauml_defaultFontBoldOblique;
%metauml_defaultFontBoldOblique := "ul9bo8r";

input metauml;

beginfig(1);
    iActivity.foreColor := (0.8, 0.8, 1.0);
    iState.foreColor := (1.0, 1.0, 0.85);
    iAssoc.iFont.scale := 0.9;

    Begin.b;

    Activity.a_ar("after-rollback", "handlers");
    Activity.a_body("body of", "atomic block");
    a_body.info.foreColor := (0.9, 1.0, 0.9);
    Activity.a_bc("before-commit", "handlers");

    State.a("Active", "nesting-level = 0")(a_ar, a_body, a_bc);
    a.info.left := a.info.right := 10;
    %a.info.drawNameLine := 1;
    topToBottom(40)(a_ar, a_body);
    topToBottom(20)(a_body, a_bc);


    Activity.aa_ar("after-rollback", "handlers");
    Activity.aa_body("body of", "atomic block");
    aa_body.info.foreColor := (0.9, 1.0, 0.9);

    State.aa("Active", "nesting-level = 1")(aa_ar, aa_body);
    aa.info.left := aa.info.right := 10;
    %aa.info.drawNameLine := 1;
    topToBottom(40)(aa_ar, aa_body);


    Activity.aaa_ar("after-rollback", "handlers");
    Activity.aaa_body("body of", "atomic block");
    aaa_body.info.foreColor := (0.9, 1.0, 0.9);

    State.aaa("Active", "nesting-level = 2")(aaa_ar, aaa_body);
    aaa.info.left := aaa.info.right := 10;
    %aaa.info.drawNameLine := 1;
    topToBottom(40)(aaa_ar, aaa_body);


    Activity.aaaa_ar("", "");
    Activity.aaaa_body("", "");
    aaaa_body.info.foreColor := (0.9, 1.0, 0.9);

    State.aaaa("Active", "nesting-level > 2", "")();
    aaaa.info.left := aaaa.info.right := 0;
    aaaa.info.bottom := 92;
    aaaa.info.foreColor := (1.0, 1.0, 0.9);
    aaaa.info.borderColor := (0.5, 0.5, 0.5);

    Activity.preparing_ext("external resources", "prepare");

    State.preparing("Preparing", "")(preparing_ext);
    preparing.info.left := preparing.info.right := 10;
    %preparing.info.drawNameLine := 1;


    Activity.prepared_d("external", "decider");

    State.prepared("Prepared", "")(prepared_d);
    prepared.info.left := prepared.info.right := 10;
    %prepared.info.drawNameLine := 1;


    State.undecided("undecided states, commit or rollback possible", "")(a, aa, aaa, aaaa, preparing, prepared);    
    undecided.info.left := undecided.info.right := 20;
    %undecided.info.drawNameLine := 1;
    undecided.info.foreColor := (1.0, 1.0, 0.95);

    aa.nw = a.ne + (40, 0);
    aaa.nw = aa.ne + (30, 0);
    aaaa.nw = aaa.ne + (30, 0);
    %preparing.width = a.width;
    %prepared.width = a.width;
    topToBottom(40)(aa, preparing);
    prepared_d.c = preparing_ext.c + (aaa_body.c - aa_body.c);

    Activity.committed_ext("external resources", "commit");
    Activity.committed_ac("after-commit", "handlers");
    End.committed_e;

    State.committed("Committed", "")(committed_ext, committed_ac, committed_e);
    committed.info.left := committed.info.right := 20;
    %committed.info.drawNameLine := 1;
    topToBottom(20)(committed_ext, committed_ac, committed_e);


    Activity.rolledback_ext("external resources", "rollback");
    Activity.rolledback_ar("after-rollback", "handlers");
    End.rolledback_e;

    State.rolledback("RolledBack", "")(rolledback_ext, rolledback_ar, rolledback_e);
    rolledback.info.left := rolledback.info.right := 20;
    %rolledback.info.drawNameLine := 1;
    topToBottom(20)(rolledback_ext, rolledback_ar, rolledback_e);

    %committed.width = a.width;
    leftToRight(70)(b, a_body);
    topToBottom(50)(prepared, committed);
    rolledback.c = committed.c - (aaa_body.c - a_body.c);

    State.attempt("Txn lifetime - one atomic execution attempt", "")(b, undecided, committed, rolledback);
    attempt.info.foreColor := (1.0, 1.0, 0.98);
    %drawObjects(b, undecided, committed, rolledback);
    drawObject(attempt);

    clink(transition)(b, a_body);
    clink(transition)(a_ar, a_body);
    clink(transition)(a_body, a_bc);
    pair a_mid;
    a_mid = 0.5[a_body.c, aa_body.c];
    link(transition)(pathCut(a_body, aa_body)(a_body.c .. (a_mid + (0, 5)) .. aa_body.c));
    item(iAssoc)("nested atomic")(obj.s = (a_mid + (0, 5)));
    link(transition)(pathCut(aa_body, a_body)(aa_body.c .. (a_mid + (0, -5)) .. a_body.c));
    item(iAssoc)("nested commit")(obj.n = (a_mid + (0, -5)));
    link(transition)(pathCut(aa_body, a_ar)(aa_body.c -- a_ar.c));
    item(iAssoc)("partial rollback")(obj.n = 0.5[a_ar.c, aa_ar.c] + (0, -5));

    clink(transition)(aa_ar, aa_body);
    pair aa_mid;
    aa_mid = 0.5[aa_body.c, aaa_body.c];
    link(transition)(pathCut(aa_body, aaa_body)(aa_body.c .. (aa_mid + (0, 5)) .. aaa_body.c));
    link(transition)(pathCut(aaa_body, aa_body)(aaa_body.c .. (aa_mid + (0, -5)) .. aa_body.c));
    link(transition)(pathCut(aaa_body, aa_ar)(aaa_body.c -- aa_ar.c));

    clink(transition)(aaa_ar, aaa_body);
    pair aaaa_body_c;
    aaaa_body_c = aaa_body.c + (aaaa.c - aaa.c);
    pair aaa_mid;
    aaa_mid = 0.5[aaa_body.c, aaaa_body_c];
    link(transition)(pathCut(aaa_body, aaaa)(aaa_body.c .. (aaa_mid + (0, 5)) .. aaaa_body_c));
    link(transition)(pathCut(aaaa, aaa_body)(aaaa_body_c .. (aaa_mid + (0, -5)) .. aaa_body.c));
    link(transition)(pathCut(aaaa, aaa_ar)(aaaa_body_c -- aaa_ar.c));

    pair aa_c;
    aa_c = 0.5[aa_ar.c, aa_body.c];
    link(transition)(pathCut(aaa_body, a_ar)(aaa_body.c .. (aa_c + (5, -0.5)) ..  (aa_c + (-5, 0.5)) .. a_ar.c));

    pair aaa_c;
    aaa_c = 0.5[aaa_ar.c, aaa_body.c];
    link(transition)(pathCut(aaaa, aa_ar)(aaaa_body_c .. (aaa_c + (5, -0.5)) ..  (aaa_c + (-5, 0.5)) .. aa_ar.c));

    link(transition)(pathCut(aaaa, a_ar)(aaaa_body_c .. (aaa_c + (0, -5)) ..  (aa_c + (0, 5)) .. a_ar.c));

    clink(transition)(a_bc, preparing_ext);
    clink(transition)(preparing_ext, prepared_d);
    clink(transition)(preparing_ext, committed_ext);
    clink(transition)(prepared_d, committed_ext);
    clink(transition)(committed_ext, committed_ac);
    clink(transition)(committed_ac, committed_e);
    item(iAssoc)("top-level commit")(obj.e = 0.5[prepared_d.c, committed_ext.c] + (-5, 5));
    item(iAssoc)("top-level rollback")(obj.w = rolledback.nw + (-25, 15));
    %clink(transition)(undecided, rolledback_ext);
    link(transition)(pathCut(undecided, rolledback_ext)(a.s .. (undecided.sw + (-20, 0)) .. rolledback_ext.c));
    clink(transition)(rolledback_ext, rolledback_ar);
    clink(transition)(rolledback_ar, rolledback_e);

endfig;
end


























































































































