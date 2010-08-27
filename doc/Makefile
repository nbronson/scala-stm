
WORKDIR=../target/metapost

all: txn_lifecycle.pdf

%.pdf: %.mp
	mkdir -p $(WORKDIR)
	rm -f $(WORKDIR)/$<
	cp $< $(WORKDIR)
	cd $(WORKDIR) ; mptopdf $<
	cp $(WORKDIR)/`basename $< .mp`-1.pdf $@

