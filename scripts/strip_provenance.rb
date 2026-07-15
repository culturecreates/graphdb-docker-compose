require 'linkeddata'
require 'sparql'
require 'stringio'

SCRIPT_DIR = File.expand_path(__dir__)
SPARQL_DIR = File.expand_path('../sparql', SCRIPT_DIR)

PROV_QUERY_PATH       = File.join(SPARQL_DIR, 'delete_prov_predicates.sparql')
BLANK_NODE_QUERY_PATH = File.join(SPARQL_DIR, 'delete_blank_node_provenance_objects.sparql')

input_path, output_path = ARGV

if input_path.nil? || output_path.nil?
  warn "Usage: ruby #{$PROGRAM_NAME} <input.ttls> <output.ttl>"
  exit 1
end

def load_query(path)
  raise "SPARQL file not found: #{path}" unless File.exist?(path)

  File.read(path)
end

puts "Loading Turtle-star dump: #{input_path} (plain RDF 1.1 grammar, no RDF-star) …"

repo = RDF::Repository.new

# Capture stderr during the read so we can count how many statements were
# dropped for containing "<<", without changing the read behavior itself —
# this is the same plain RDF::Reader.open call used previously; only the
# surrounding instrumentation is new.
captured_stderr = StringIO.new
original_stderr = $stderr
$stderr = captured_stderr

begin
  RDF::Reader.open(input_path) { |reader| repo.insert(reader) }
ensure
  $stderr = original_stderr
end

parser_log = captured_stderr.string
# Echo the captured log to stdout so it's still visible in CI job output,
# just no longer treated as evidence of failure.
puts parser_log unless parser_log.empty?

dropped_star_lines = parser_log.lines.count { |l| l.include?('<<') }

puts "  Loaded #{repo.count} statements."
puts "  Parser dropped #{dropped_star_lines} malformed-for-RDF-1.1 statement(s) containing \"<<\"."

if dropped_star_lines.zero?
  warn "::warning::No RDF-star (\"<<\") parse errors were logged while reading " \
       "#{input_path}. If this source is expected to contain RDF-star " \
       "provenance annotations, this may indicate the export format changed " \
       "or the reader's behavior changed — worth a manual check."
end

prov_query       = load_query(PROV_QUERY_PATH)
blank_node_query = load_query(BLANK_NODE_QUERY_PATH)

puts "Deleting blank nodes used only as objects of prov: triples …"
SPARQL.execute(blank_node_query, repo, update: true)
puts "  Repository after blank-node cascade delete: #{repo.count} statements."

puts "Deleting direct prov: predicate triples …"
SPARQL.execute(prov_query, repo, update: true)
puts "  Repository after prov: predicate delete: #{repo.count} statements."

# ---------------------------------------------------------------------------
# Safety check: confirm no plain prov: predicate triples remain.
#
# This does NOT re-run the deletion logic — it independently re-derives the
# condition that should now be impossible, via its own COUNT query, and
# fails loudly if it's non-zero.
# ---------------------------------------------------------------------------
puts "Verifying no prov: predicate triples remain …"

remaining_prov = SPARQL.execute(<<~SPARQL, repo)
  PREFIX prov: <http://www.w3.org/ns/prov#>
  SELECT (COUNT(*) AS ?count) WHERE {
    ?s ?p ?o .
    FILTER(STRSTARTS(STR(?p), STR(prov:)))
  }
SPARQL

prov_count = remaining_prov.first[:count].to_i

if prov_count.positive?
  warn "::error::Provenance stripping incomplete — #{prov_count} prov: " \
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
# rdf_star option cannot emit "<<" — but since the entire star-removal
# strategy here rests on the repository never having star statements in the
# first place, it's worth confirming the output file directly rather than
# only trusting that reasoning.
star_syntax_found = File.foreach(output_path).any? { |line| line.include?('<<') }

if star_syntax_found
  warn "::error::Output file #{output_path} unexpectedly contains \"<<\" " \
       "after writing — investigate before shipping this file."
  exit 1
end

puts "  Verified: output file contains no RDF-star (\"<<\") syntax."
puts "Done. Wrote #{repo.count} statements to #{output_path}."
