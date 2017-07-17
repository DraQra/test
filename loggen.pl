#!/usr/bin/perl

use strict;
use warnings;

use Time::HiRes qw(time usleep);
use IO::Handle;

use POSIX qw(strftime);

my ($rate, $outfile, $file1, $file2) = @ARGV;
my @files = ($file1, $file2);

my $fh = undef;
my $fcnt = 0;
my $linecnt = 0;
my $out = undef;

my $start = time;
my $end = undef;

while (1) {
	if (!$fh) {
		open $fh, '<', $files[$fcnt%2] or die "died";
		open $out, '>', $outfile or die "output file open failed";
		$out->autoflush;
	}
	my $prev = undef;
	while (<$fh>) {
		my $line = $_;
		my $time = time;
		my $date = strftime "%Y-%m-%d %H:%M:%S", localtime $time;
		$date .= sprintf ".%03d", ($time - int($time))*1000;
		$line =~ s/^\[.*?\]/[$date]/g;
		if ($line =~ /^\[/) {
			if ($prev) {
				print $out $prev;
			}
			$linecnt++;
			if ($linecnt % $rate == 0) {
				my $elapsed = time - $start;
				my $sleptFor = 1 - $elapsed;
				if ($sleptFor > 0) {
					select(undef, undef, undef, $sleptFor);
				}
				$start = time;
			}
			$prev = $line;
		} else {
			$prev .= $line;
		}
	}
	print $out $prev;
	close $fh;
	close $out;
	$fh = undef;
	$prev = undef;
	$linecnt = 0;
	$fcnt++;
}
