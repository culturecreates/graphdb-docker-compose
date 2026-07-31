require 'linkeddata'
require 'tmpdir'
require 'fileutils'

# sparql/delete_blank_node_provenance_objects.sparql and
# sparql/delete_prov_predicates.sparql document the provenance-removal logic
# implemented natively below (see the comment further down for why this
# script no longer executes them via the `sparql` gem).

input_path, output_path = ARGV

if input_path.nil? || output_path.nil?
  warn "Usage: ruby #{$PROGRAM_NAME} <input.ttls> <output.ttl>"
  exit 1
end

# Streaming replacement for $stderr during the read. Counts lines containing
# "<<" without ever retaining more than one partial line in memory, and
# without echoing anything to the real stderr/stdout. This is a backstop
# for the (expected to be rare, post-pre-filter) case of a remaining
# RDF-star line reaching the reader at all.
class CountingErrorSink
  attr_reader :dropped_count, :total_lines

  def initialize
    @dropped_count = 0
    @total_lines = 0
    @partial = String.new
  end

  def write(str)
    @partial << str
    while (newline_index = @partial.index("\n"))
      line = @partial.slice!(0..newline_index)
      @total_lines += 1
      @dropped_count += 1 if line.include?('<<')
    end
    str.bytesize
  end

  def flush
    unless @partial.empty?
      @total_lines += 1
      @dropped_count += 1 if @partial.include?('<<')
      @partial.clear
    end
  end
end

puts "Pre-filtering RDF-star annotation lines out of #{input_path} …"

filtered_path = File.join(Dir.mktmpdir('strip-provenance'), 'filtered.ttl')
star_lines_removed = 0

File.open(filtered_path, 'w') do |out|
  File.foreach(input_path) do |line|
    if line.lstrip.start_with?('<<')
      star_lines_removed += 1
    else
      out << line
    end
  end
end

puts "  Removed #{star_lines_removed} RDF-star annotation line(s) before parsing."

if star_lines_removed.zero?
  warn "::warning::No lines starting with \"<<\" were found in #{input_path}. " \
       "If this source is expected to contain RDF-star provenance " \
       "annotations, this may indicate the export format changed -- " \
       "worth a manual check."
end

puts "Loading pre-filtered Turtle dump (plain RDF 1.1 grammar) …"

repo = RDF::Repository.new

error_sink = CountingErrorSink.new
original_stderr = $stderr
$stderr = error_sink

begin
  RDF::Reader.open(filtered_path) { |reader| repo.insert(reader) }
ensure
  error_sink.flush
  $stderr = original_stderr
end

puts "  Loaded #{repo.count} statements."

if error_sink.total_lines.positive?
  puts "  Note: parser still logged #{error_sink.total_lines} line(s) to " \
       "stderr (#{error_sink.dropped_count} containing \"<<\") after " \
       "pre-filtering -- see the note above about multi-line annotations."
end

# ---------------------------------------------------------------------------
# Provenance deletion -- implemented natively in Ruby, NOT via SPARQL.execute.
#
# sparql/delete_blank_node_provenance_objects.sparql and
# sparql/delete_prov_predicates.sparql still document the intended logic
# (and are the right form to run against a real triple store like GraphDB,
# which has a query planner and indices). But the `sparql` gem's algebra
# evaluator has no query planner. The blank-node query joins two
# unbound-predicate patterns (?s ?provPred ?bnode against ?bnode ?p ?o)
# RDF::Repository's own `query` method, by contrast, is indexed by 
# subject/predicate/object and does a plain linear scan plus indexed lookups here 
# the same logic, without the join.
#
# All traversals below use `each_statement` with an explicit block and an
# explicit accumulator array/counter -- never `.select` / `.map` / `.count`
# chained off a bare, no-block `each_statement` call. See the "Enumerable
# safety note" above.
# ---------------------------------------------------------------------------

PROV_NS = 'http://www.w3.org/ns/prov#'

def prov_predicate?(predicate)
  predicate.to_s.start_with?(PROV_NS)
end

puts "Deleting blank nodes used only as objects of prov: triples …"

blank_node_targets = []
repo.each_statement do |s|
  blank_node_targets << s.object if prov_predicate?(s.predicate) && s.object.node?
end
blank_node_targets.uniq!

blank_node_targets.each do |bnode|
  # Indexed lookup by subject, not a full scan.
  matching = repo.query(subject: bnode).to_a
  matching.each { |stmt| repo.delete(stmt) }
end

puts "  Repository after blank-node cascade delete: #{repo.count} statements " \
     "(#{blank_node_targets.size} blank node(s) removed)."

puts "Deleting direct prov: predicate triples …"

# One more linear scan -- cheap on its own, this is the operation that was
# previously (incorrectly) expressed as a join. Uses an explicit block for
# the same reason as above.
to_delete = []
repo.each_statement do |s|
  to_delete << s if prov_predicate?(s.predicate)
end
to_delete.each { |stmt| repo.delete(stmt) }

puts "  Repository after prov: predicate delete: #{repo.count} statements " \
     "(#{to_delete.size} triple(s) removed)."

puts "Verifying no prov: predicate triples remain …"

prov_count = 0
repo.each_statement do |s|
  prov_count += 1 if prov_predicate?(s.predicate)
end

if prov_count.positive?
  warn "::error::Provenance stripping incomplete -- #{prov_count} prov: " \
       "predicate triple(s) remain after cleanup."
  exit 1
end

puts "  Verified: 0 prov: predicate triples remain."

puts "Writing RDF 1.1 Turtle to #{output_path} …"
RDF::Turtle::Writer.open(
  output_path,
  prefixes: {
    schema:  'http://schema.org/',
    xsd:     'http://www.w3.org/2001/XMLSchema#',
    owl:     'http://www.w3.org/2002/07/owl#',
    rdf:     'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
    rdfs:    'http://www.w3.org/2000/01/rdf-schema#',
    skos:    'http://www.w3.org/2004/02/skos/core#',
    ad:      'http://kg.artsdata.ca/resource/',
    admodel: 'http://kg.artsdata.ca/culture-creates/artsdata-data-model/',
  }
) do |writer|
  writer << repo
end

# Independent, cheap final guarantee: RDF::Turtle::Writer without an
# rdf_star option cannot emit "<<" -- but since the entire star-removal
# strategy here rests on the repository never having star statements in the
# first place, it's worth confirming the output file directly rather than
# only trusting that reasoning. (File.foreach here returns a standard
# library Enumerator, not an RDF.rb one, so `.any?` chained off it is not
# subject to the same concern as the RDF::Repository traversals above.)
star_syntax_found = File.foreach(output_path).any? { |line| line.include?('<<') }

if star_syntax_found
  warn "::error::Output file #{output_path} unexpectedly contains \"<<\" " \
       "after writing -- investigate before shipping this file."
  exit 1
end

puts "  Verified: output file contains no RDF-star (\"<<\") syntax."
puts "Done. Wrote #{repo.count} statements to #{output_path}."

FileUtils.remove_entry(File.dirname(filtered_path))